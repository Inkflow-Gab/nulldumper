//! Small, bounded APK reader used by the `--apk` convenience workflow.
//!
//! It only scans ZIP central-directory records and streams the selected IL2CPP
//! payloads to disk. It never unpacks resources, dex files, or the rest of an APK.

use std::fs::{self, File};
use std::io::{Read, Seek, SeekFrom, Write};
use std::path::{Path, PathBuf};

use flate2::read::DeflateDecoder;

const EOCD_SIGNATURE: u32 = 0x0605_4b50;
const CENTRAL_SIGNATURE: u32 = 0x0201_4b50;
const LOCAL_SIGNATURE: u32 = 0x0403_4b50;
const MAX_EOCD_SEARCH: usize = 22 + u16::MAX as usize;
const MAX_PAYLOAD_SIZE: u64 = 2 * 1024 * 1024 * 1024;

#[derive(Debug)]
struct ZipEntry {
    name: String,
    method: u16,
    crc32: u32,
    compressed_size: u64,
    uncompressed_size: u64,
    local_header_offset: u64,
}

#[derive(Debug)]
struct Candidate {
    archive: PathBuf,
    entry: ZipEntry,
    abi: Option<String>,
}

#[derive(Debug, Clone)]
pub struct ApkPayload {
    pub binary_path: PathBuf,
    pub metadata_path: PathBuf,
    pub abi: String,
    pub binary_entry: String,
    pub metadata_entry: String,
}

/// Finds and extracts the best matching IL2CPP library plus metadata from one
/// or more APKs. The callback is called while archives are being inspected.
pub fn extract_apks<F>(
    apks: &[PathBuf],
    output_dir: &Path,
    requested_abi: Option<&str>,
    mut progress: F,
) -> Result<ApkPayload, String>
where
    F: FnMut(usize, usize, &str),
{
    if apks.is_empty() {
        return Err("No APK files were supplied. Use --apk <file.apk> (repeat for split APKs).".into());
    }
    fs::create_dir_all(output_dir)
        .map_err(|e| format!("Cannot create temporary extraction directory: {e}"))?;

    let mut binaries = Vec::new();
    let mut metadata = Vec::new();
    let mut nested_apk_bundle = false;
    let total_steps = apks.len() + 2;
    for (index, path) in apks.iter().enumerate() {
        progress(index, total_steps, &format!("Inspecting {}", path.display()));
        if !path.is_file() {
            return Err(format!("APK not found or not a regular file: {}", path.display()));
        }
        let entries = read_directory(path)?;
        nested_apk_bundle |= entries.iter().any(|entry| entry.name.to_ascii_lowercase().ends_with(".apk"));
        for entry in entries {
            if let Some(abi) = library_abi(&entry.name) {
                binaries.push(Candidate {
                    archive: path.clone(),
                    entry,
                    abi: Some(abi.to_owned()),
                });
            } else if is_metadata_path(&entry.name) {
                metadata.push(Candidate {
                    archive: path.clone(),
                    entry,
                    abi: None,
                });
            }
        }
        progress(index + 1, total_steps, &format!("Inspected {}", path.display()));
    }
    progress(apks.len(), total_steps, "Choosing compatible IL2CPP payloads");

    if binaries.is_empty() {
        if nested_apk_bundle {
            return Err("This looks like an .apks bundle containing nested APKs. Unpack its split APK files first, then pass each file with a repeated --apk option.".into());
        }
        return Err(format!(
            "No libil2cpp.so was found in {} APK(s). Make sure these are the game's APKs, including any ABI split APK.",
            apks.len()
        ));
    }
    if metadata.is_empty() {
        return Err(format!(
            "No global-metadata.dat was found in {} APK(s). It is often under assets/bin/Data/Managed/Metadata; protected games may not package readable metadata.",
            apks.len()
        ));
    }

    let abi = choose_abi(&binaries, requested_abi)?;
    binaries.retain(|candidate| candidate.abi.as_deref() == Some(abi.as_str()));
    binaries.sort_by_key(|candidate| {
        // Prefer a regular, uncompressed/normal path over odd nested-looking paths,
        // then choose deterministically by archive and entry name.
        (
            candidate.entry.name.matches('/').count(),
            candidate.archive.clone(),
            candidate.entry.name.clone(),
        )
    });
    metadata.sort_by_key(|candidate| {
        let path_rank = if candidate.entry.name.eq_ignore_ascii_case(
            "assets/bin/Data/Managed/Metadata/global-metadata.dat",
        ) {
            0
        } else {
            1
        };
        (path_rank, candidate.archive.clone(), candidate.entry.name.clone())
    });

    let binary_path = output_dir.join("libil2cpp.so");
    let metadata_path = output_dir.join("global-metadata.dat");

    let mut binary_errors = Vec::new();
    progress(apks.len(), total_steps, "Extracting libil2cpp.so");
    let binary = binaries.into_iter().find_map(|candidate| {
        match extract_entry(&candidate, &binary_path, &mut progress) {
            Ok(()) => Some(candidate),
            Err(error) => {
                binary_errors.push(format!("{} in {}: {error}", candidate.entry.name, candidate.archive.display()));
                None
            }
        }
    }).ok_or_else(|| format!("Could not extract a usable libil2cpp.so for ABI {abi}: {}", binary_errors.join("; ")))?;

    let mut metadata_errors = Vec::new();
    progress(apks.len() + 1, total_steps, "Extracting global-metadata.dat");
    let metadata_candidate = metadata.into_iter().find_map(|candidate| {
        match extract_entry(&candidate, &metadata_path, &mut progress) {
            Ok(()) => Some(candidate),
            Err(error) => {
                metadata_errors.push(format!("{} in {}: {error}", candidate.entry.name, candidate.archive.display()));
                None
            }
        }
    }).ok_or_else(|| format!("Could not extract a usable global-metadata.dat: {}", metadata_errors.join("; ")))?;

    // Do not reject encrypted/custom metadata here; the main dumper has
    // supported decryptors and much better format diagnostics.

    progress(total_steps, total_steps, "APK payloads ready");
    Ok(ApkPayload {
        binary_path,
        metadata_path,
        abi,
        binary_entry: binary.entry.name.clone(),
        metadata_entry: metadata_candidate.entry.name.clone(),
    })
}

fn read_directory(path: &Path) -> Result<Vec<ZipEntry>, String> {
    let mut file = File::open(path)
        .map_err(|e| format!("Cannot open {}: {e}", path.display()))?;
    let file_len = file
        .metadata()
        .map_err(|e| format!("Cannot read {}: {e}", path.display()))?
        .len();
    if file_len < 22 {
        return Err(format!("{} is too small to be an APK/ZIP archive", path.display()));
    }

    let tail_len = file_len.min(MAX_EOCD_SEARCH as u64) as usize;
    file.seek(SeekFrom::End(-(tail_len as i64)))
        .map_err(|e| format!("Cannot seek in {}: {e}", path.display()))?;
    let mut tail = vec![0; tail_len];
    file.read_exact(&mut tail)
        .map_err(|e| format!("Cannot read ZIP footer in {}: {e}", path.display()))?;
    let eocd_offset = (0..=tail.len().saturating_sub(22))
        .rev()
        .find(|&offset| {
            le_u32(&tail, offset).ok() == Some(EOCD_SIGNATURE)
                && le_u16(&tail, offset + 20)
                    .ok()
                    .is_some_and(|comment_len| offset + 22 + comment_len as usize == tail.len())
        })
        .ok_or_else(|| format!("{} is not a valid ZIP/APK (ZIP footer not found)", path.display()))?;
    let eocd = &tail[eocd_offset..];
    let disk = le_u16(eocd, 4)?;
    let directory_disk = le_u16(eocd, 6)?;
    let entries_on_disk = le_u16(eocd, 8)?;
    let entries_total = le_u16(eocd, 10)?;
    let directory_size = le_u32(eocd, 12)?;
    let directory_offset = le_u32(eocd, 16)?;
    if disk != 0 || directory_disk != 0 || entries_on_disk != entries_total {
        return Err(format!("{} uses a multi-disk ZIP format, which is not supported", path.display()));
    }
    if entries_total == u16::MAX || directory_size == u32::MAX || directory_offset == u32::MAX {
        return Err(format!("{} uses ZIP64; this APK layout is not supported yet", path.display()));
    }
    let directory_end = u64::from(directory_offset) + u64::from(directory_size);
    if directory_end > file_len {
        return Err(format!("{} has an invalid ZIP central-directory offset", path.display()));
    }

    file.seek(SeekFrom::Start(u64::from(directory_offset)))
        .map_err(|e| format!("Cannot seek to ZIP directory in {}: {e}", path.display()))?;
    let mut entries = Vec::new();
    for _ in 0..entries_total {
        let mut fixed = [0u8; 46];
        file.read_exact(&mut fixed).map_err(|e| {
            format!("{} has a truncated ZIP central directory: {e}", path.display())
        })?;
        if le_u32(&fixed, 0)? != CENTRAL_SIGNATURE {
            return Err(format!("{} has a malformed ZIP central directory", path.display()));
        }
        let flags = le_u16(&fixed, 8)?;
        let method = le_u16(&fixed, 10)?;
        let crc32 = le_u32(&fixed, 16)?;
        let compressed_size = le_u32(&fixed, 20)?;
        let uncompressed_size = le_u32(&fixed, 24)?;
        let name_len = le_u16(&fixed, 28)? as usize;
        let extra_len = le_u16(&fixed, 30)? as u64;
        let comment_len = le_u16(&fixed, 32)? as u64;
        let local_header_offset = le_u32(&fixed, 42)?;
        if flags & 1 != 0 {
            // APKs can be signed but their payload entries themselves should
            // not be encrypted. Keep searching the archive, but do not select
            // encrypted candidate entries.
        }
        let mut name_bytes = vec![0; name_len];
        file.read_exact(&mut name_bytes).map_err(|e| {
            format!("{} has a truncated ZIP entry name: {e}", path.display())
        })?;
        let name = String::from_utf8_lossy(&name_bytes).into_owned();
        file.seek(SeekFrom::Current((extra_len + comment_len) as i64))
            .map_err(|e| format!("Cannot skip ZIP entry fields in {}: {e}", path.display()))?;
        if file
            .stream_position()
            .map_err(|e| format!("Cannot inspect ZIP directory in {}: {e}", path.display()))?
            > directory_end
        {
            return Err(format!("{} has an entry extending past its central directory", path.display()));
        }

        let sentinel = u32::MAX;
        let has_zip64_sizes = compressed_size == sentinel || uncompressed_size == sentinel;
        let has_zip64_offset = local_header_offset == sentinel;
        if (has_zip64_sizes || has_zip64_offset)
            && (is_metadata_path(&name) || library_abi(&name).is_some())
        {
            return Err(format!("{} contains a ZIP64 IL2CPP payload; ZIP64 extraction is not supported yet", path.display()));
        }
        if flags & 1 == 0 && !name.ends_with('/') {
            entries.push(ZipEntry {
                name,
                method,
                crc32,
                compressed_size: u64::from(compressed_size),
                uncompressed_size: u64::from(uncompressed_size),
                local_header_offset: u64::from(local_header_offset),
            });
        }
    }
    Ok(entries)
}

fn extract_entry<F>(candidate: &Candidate, destination: &Path, progress: &mut F) -> Result<(), String>
where
    F: FnMut(usize, usize, &str),
{
    let entry = &candidate.entry;
    progress(0, 0, &format!("Extracting {}", entry.name));
    if entry.uncompressed_size > MAX_PAYLOAD_SIZE {
        return Err(format!(
            "payload is unusually large ({} bytes; safety limit is {} bytes)",
            entry.uncompressed_size, MAX_PAYLOAD_SIZE
        ));
    }
    if entry.method != 0 && entry.method != 8 {
        return Err(format!("unsupported ZIP compression method {}", entry.method));
    }
    let mut input = File::open(&candidate.archive)
        .map_err(|e| format!("cannot open archive: {e}"))?;
    input
        .seek(SeekFrom::Start(entry.local_header_offset))
        .map_err(|e| format!("cannot seek to payload: {e}"))?;
    let mut header = [0u8; 30];
    input
        .read_exact(&mut header)
        .map_err(|e| format!("truncated local ZIP header: {e}"))?;
    if le_u32(&header, 0)? != LOCAL_SIGNATURE {
        return Err("invalid local ZIP header signature".into());
    }
    let local_method = le_u16(&header, 8)?;
    if local_method != entry.method {
        return Err("ZIP local-header compression differs from directory record".into());
    }
    let name_len = le_u16(&header, 26)? as u64;
    let extra_len = le_u16(&header, 28)? as u64;
    let data_offset = entry
        .local_header_offset
        .checked_add(30 + name_len + extra_len)
        .ok_or_else(|| "ZIP payload offset overflow".to_string())?;
    input
        .seek(SeekFrom::Start(data_offset))
        .map_err(|e| format!("cannot seek to compressed payload: {e}"))?;

    let parent = destination
        .parent()
        .ok_or_else(|| "invalid extraction destination".to_string())?;
    fs::create_dir_all(parent).map_err(|e| format!("cannot create output directory: {e}"))?;
    let mut output = File::create(destination)
        .map_err(|e| format!("cannot create extracted file: {e}"))?;
    let bounded = input.take(entry.compressed_size);
    let mut reader: Box<dyn Read> = match entry.method {
        0 => Box::new(bounded),
        8 => Box::new(DeflateDecoder::new(bounded)),
        _ => unreachable!(),
    };
    let mut hasher = crc32fast::Hasher::new();
    let mut written = 0u64;
    let mut next_progress = (entry.uncompressed_size / 100).max(1);
    let mut buffer = [0u8; 64 * 1024];
    loop {
        let count = reader
            .read(&mut buffer)
            .map_err(|e| format!("could not decompress ZIP payload: {e}"))?;
        if count == 0 {
            break;
        }
        written = written
            .checked_add(count as u64)
            .ok_or_else(|| "extracted payload size overflow".to_string())?;
        if written > entry.uncompressed_size || written > MAX_PAYLOAD_SIZE {
            return Err("decompressed payload exceeds its declared/safe size".into());
        }
        hasher.update(&buffer[..count]);
        output
            .write_all(&buffer[..count])
            .map_err(|e| format!("could not write extracted payload: {e}"))?;
        if written >= next_progress {
            let percent = if entry.uncompressed_size == 0 {
                100
            } else {
                ((written.saturating_mul(100) / entry.uncompressed_size).min(100)) as usize
            };
            progress(0, 0, &format!("Extracting {} ({percent}%)", entry.name));
            next_progress = next_progress.saturating_add((entry.uncompressed_size / 100).max(1));
        }
    }
    if written != entry.uncompressed_size {
        return Err(format!(
            "truncated payload (expected {} bytes, extracted {written})",
            entry.uncompressed_size
        ));
    }
    if hasher.finalize() != entry.crc32 {
        return Err("payload CRC check failed; the APK may be damaged".into());
    }
    output.flush().map_err(|e| format!("could not flush extracted file: {e}"))?;
    Ok(())
}

fn choose_abi(candidates: &[Candidate], requested: Option<&str>) -> Result<String, String> {
    const PREFERENCE: [&str; 5] = ["arm64-v8a", "armeabi-v7a", "armeabi", "x86_64", "x86"];
    if let Some(abi) = requested {
        if candidates.iter().any(|candidate| candidate.abi.as_deref() == Some(abi)) {
            return Ok(abi.to_owned());
        }
        let found = available_abis(candidates).join(", ");
        let found = if found.is_empty() { "none" } else { found.as_str() };
        return Err(format!(
            "Requested ABI '{abi}' was not found. APK set contains: {found}"
        ));
    }
    PREFERENCE
        .iter()
        .find(|abi| candidates.iter().any(|candidate| candidate.abi.as_deref() == Some(**abi)))
        .map(|abi| (*abi).to_string())
        .ok_or_else(|| {
            format!(
                "No supported Android ABI was found. APK set contains: {}",
                available_abis(candidates).join(", ")
            )
        })
}

fn available_abis(candidates: &[Candidate]) -> Vec<String> {
    let mut abis: Vec<String> = candidates.iter().filter_map(|item| item.abi.clone()).collect();
    abis.sort();
    abis.dedup();
    abis
}

fn is_metadata_path(name: &str) -> bool {
    name.rsplit('/').next().is_some_and(|leaf| leaf.eq_ignore_ascii_case("global-metadata.dat"))
}

fn library_abi(name: &str) -> Option<&str> {
    let mut parts = name.split('/');
    if parts.next()? != "lib" {
        return None;
    }
    let abi = parts.next()?;
    if parts.next()? != "libil2cpp.so" || parts.next().is_some() {
        return None;
    }
    matches!(abi, "arm64-v8a" | "armeabi-v7a" | "armeabi" | "x86_64" | "x86").then_some(abi)
}

fn le_u16(bytes: &[u8], at: usize) -> Result<u16, String> {
    let slice = bytes
        .get(at..at + 2)
        .ok_or_else(|| "truncated ZIP integer field".to_string())?;
    Ok(u16::from_le_bytes([slice[0], slice[1]]))
}

fn le_u32(bytes: &[u8], at: usize) -> Result<u32, String> {
    let slice = bytes
        .get(at..at + 4)
        .ok_or_else(|| "truncated ZIP integer field".to_string())?;
    Ok(u32::from_le_bytes([slice[0], slice[1], slice[2], slice[3]]))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn recognizes_android_library_paths() {
        assert_eq!(library_abi("lib/arm64-v8a/libil2cpp.so"), Some("arm64-v8a"));
        assert_eq!(library_abi("lib/x86/libil2cpp.so"), Some("x86"));
        assert_eq!(library_abi("lib/arm64-v8a/libunity.so"), None);
        assert_eq!(library_abi("assets/lib/arm64-v8a/libil2cpp.so"), None);
    }

    #[test]
    fn recognizes_metadata_in_any_standard_subdirectory() {
        assert!(is_metadata_path("assets/bin/Data/Managed/Metadata/global-metadata.dat"));
        assert!(is_metadata_path("global-metadata.dat"));
        assert!(!is_metadata_path("assets/bin/Data/Managed/Metadata/metadata.dat"));
    }

    #[test]
    fn scans_and_extracts_only_the_selected_payloads() {
        let nonce = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        let root = std::env::temp_dir().join(format!("apk-reader-test-{}-{nonce}", std::process::id()));
        fs::create_dir_all(&root).unwrap();
        let apk_path = root.join("game.apk");
        let output = root.join("out");
        write_test_zip(&apk_path, &[
            ("lib/arm64-v8a/libil2cpp.so", b"ELF sample".as_slice()),
            ("assets/bin/Data/Managed/Metadata/global-metadata.dat", b"metadata sample".as_slice()),
            ("classes.dex", b"must not be extracted".as_slice()),
        ]);

        let result = extract_apks(&[apk_path], &output, None, |_, _, _| {}).unwrap();
        assert_eq!(result.abi, "arm64-v8a");
        assert_eq!(fs::read(result.binary_path).unwrap(), b"ELF sample");
        assert_eq!(fs::read(result.metadata_path).unwrap(), b"metadata sample");
        assert_eq!(fs::read_dir(&output).unwrap().count(), 2);
        fs::remove_dir_all(root).unwrap();
    }

    fn write_test_zip(path: &Path, entries: &[(&str, &[u8])]) {
        let mut archive = Vec::new();
        let mut directory = Vec::new();
        for (name, contents) in entries {
            let name_bytes = name.as_bytes();
            let offset = archive.len() as u32;
            let crc = crc32fast::hash(contents);
            archive.extend_from_slice(&LOCAL_SIGNATURE.to_le_bytes());
            push_u16(&mut archive, 20); // version needed
            push_u16(&mut archive, 0); // flags
            push_u16(&mut archive, 0); // stored
            push_u16(&mut archive, 0);
            push_u16(&mut archive, 0);
            push_u32(&mut archive, crc);
            push_u32(&mut archive, contents.len() as u32);
            push_u32(&mut archive, contents.len() as u32);
            push_u16(&mut archive, name_bytes.len() as u16);
            push_u16(&mut archive, 0);
            archive.extend_from_slice(name_bytes);
            archive.extend_from_slice(contents);

            directory.extend_from_slice(&CENTRAL_SIGNATURE.to_le_bytes());
            push_u16(&mut directory, 20); // version made by
            push_u16(&mut directory, 20); // version needed
            push_u16(&mut directory, 0); // flags
            push_u16(&mut directory, 0); // stored
            push_u16(&mut directory, 0);
            push_u16(&mut directory, 0);
            push_u32(&mut directory, crc);
            push_u32(&mut directory, contents.len() as u32);
            push_u32(&mut directory, contents.len() as u32);
            push_u16(&mut directory, name_bytes.len() as u16);
            push_u16(&mut directory, 0);
            push_u16(&mut directory, 0);
            push_u16(&mut directory, 0);
            push_u16(&mut directory, 0);
            push_u32(&mut directory, 0);
            push_u32(&mut directory, offset);
            directory.extend_from_slice(name_bytes);
        }
        let directory_offset = archive.len() as u32;
        archive.extend_from_slice(&directory);
        archive.extend_from_slice(&EOCD_SIGNATURE.to_le_bytes());
        push_u16(&mut archive, 0);
        push_u16(&mut archive, 0);
        push_u16(&mut archive, entries.len() as u16);
        push_u16(&mut archive, entries.len() as u16);
        push_u32(&mut archive, directory.len() as u32);
        push_u32(&mut archive, directory_offset);
        push_u16(&mut archive, 0); // comment length
        fs::write(path, archive).unwrap();
    }

    fn push_u16(target: &mut Vec<u8>, value: u16) {
        target.extend_from_slice(&value.to_le_bytes());
    }

    fn push_u32(target: &mut Vec<u8>, value: u32) {
        target.extend_from_slice(&value.to_le_bytes());
    }

    #[test]
    fn selects_a_requested_abi_or_best_default() {
        let candidates = ["x86", "arm64-v8a", "armeabi-v7a"].map(|abi| Candidate {
            archive: PathBuf::new(),
            entry: ZipEntry {
                name: format!("lib/{abi}/libil2cpp.so"),
                method: 0,
                crc32: 0,
                compressed_size: 1,
                uncompressed_size: 1,
                local_header_offset: 0,
            },
            abi: Some(abi.to_owned()),
        });
        assert_eq!(choose_abi(&candidates, None).unwrap(), "arm64-v8a");
        assert_eq!(choose_abi(&candidates, Some("x86")).unwrap(), "x86");
        assert!(choose_abi(&candidates, Some("mips")).is_err());
    }
}
