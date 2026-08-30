import Darwin
import Foundation

/// Reclaims payload bytes from an owned orphan through an inode-bound file
/// descriptor. The entry is first moved to another self-recognized basename so
/// a crash remains retryable. No pathname is unlinked: POSIX cannot make unlink
/// conditional on inode identity, while `ftruncate` can never target a swapped
/// replacement. A zero-byte hidden tombstone can remain by design.
enum OwnedFileCleanup {
    static func deleteIfAgedRegular(
        candidate: URL,
        cutoff: Date,
        stillOwned: () -> Bool = { true },
        beforeQuarantineMove: () throws -> Void = {},
        quarantineName: () -> String = { ".hyphen-\(UUID().uuidString).part" },
        afterQuarantineBound: (URL) throws -> Void = { _ in }
    ) -> Bool {
        let descriptor = Darwin.open(candidate.path, O_WRONLY | O_NOFOLLOW | O_CLOEXEC)
        guard descriptor >= 0 else { return false }
        defer { Darwin.close(descriptor) }

        var bound = stat()
        guard Darwin.fstat(descriptor, &bound) == 0,
              isRegular(bound),
              bound.st_nlink == 1,
              modificationDate(bound) <= cutoff,
              stillOwned()
        else {
            return false
        }
        do {
            try beforeQuarantineMove()
        } catch {
            return false
        }

        let basename = quarantineName()
        guard isPlainBasename(basename) else { return false }
        let quarantine = candidate.deletingLastPathComponent().appendingPathComponent(
            basename,
            isDirectory: false
        )
        guard renameExclusively(candidate, quarantine) else { return false }

        var reclaimed = false
        defer {
            if !reclaimed {
                _ = renameExclusively(quarantine, candidate)
            }
        }

        guard let captured = metadata(quarantine),
              sameFile(captured, bound),
              stillOwned()
        else {
            return false
        }
        do {
            try afterQuarantineBound(quarantine)
        } catch {
            return false
        }
        var finalBound = stat()
        guard stillOwned(),
              Darwin.fstat(descriptor, &finalBound) == 0,
              sameFile(finalBound, bound),
              finalBound.st_nlink == 1,
              Darwin.ftruncate(descriptor, 0) == 0,
              Darwin.fsync(descriptor) == 0
        else {
            return false
        }
        reclaimed = true

        if metadata(quarantine).map({ sameFile($0, bound) }) != true {
            preserveReplacement(quarantine, original: candidate)
        }
        return true
    }

    private static func metadata(_ url: URL) -> stat? {
        var value = stat()
        guard Darwin.lstat(url.path, &value) == 0 else { return nil }
        return value
    }

    private static func isRegular(_ value: stat) -> Bool {
        value.st_mode & S_IFMT == S_IFREG
    }

    private static func sameFile(_ left: stat, _ right: stat) -> Bool {
        left.st_dev == right.st_dev && left.st_ino == right.st_ino
    }

    private static func modificationDate(_ value: stat) -> Date {
        Date(
            timeIntervalSince1970: TimeInterval(value.st_mtimespec.tv_sec) +
                TimeInterval(value.st_mtimespec.tv_nsec) / 1_000_000_000
        )
    }

    private static func renameExclusively(_ source: URL, _ destination: URL) -> Bool {
        Darwin.renamex_np(source.path, destination.path, UInt32(RENAME_EXCL)) == 0
    }

    private static func preserveReplacement(_ quarantine: URL, original: URL) {
        guard metadata(quarantine) != nil else { return }
        if metadata(original) == nil, renameExclusively(quarantine, original) { return }
        for _ in 0..<16 {
            let hold = quarantine.deletingLastPathComponent().appendingPathComponent(
                ".hyphen-preserved-\(UUID().uuidString).hold",
                isDirectory: false
            )
            if renameExclusively(quarantine, hold) { return }
        }
    }

    private static func isPlainBasename(_ value: String) -> Bool {
        !value.isEmpty && value != "." && value != ".." &&
            (value as NSString).lastPathComponent == value
    }
}
