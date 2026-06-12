import Foundation

/// Length-prefixed framing (protocol v0 §2.1, HYP-M2-012): each frame is
/// `length(4 bytes, big-endian uint32) || payload(UTF-8 JSON)`. Frames
/// above 4 MiB are rejected (`transport/frame-too-large`).
public enum FrameError: Error, Equatable {
    case tooLarge(declared: UInt64)
}

public enum FrameCodec {

    public static let maxFrameBytes = 4 * 1024 * 1024

    public static func encode(_ payload: Data) throws -> Data {
        guard payload.count <= maxFrameBytes else {
            throw FrameError.tooLarge(declared: UInt64(payload.count))
        }
        let size = UInt32(payload.count)
        var frame = Data([
            UInt8(truncatingIfNeeded: size >> 24),
            UInt8(truncatingIfNeeded: size >> 16),
            UInt8(truncatingIfNeeded: size >> 8),
            UInt8(truncatingIfNeeded: size),
        ])
        frame.append(payload)
        return frame
    }
}

/// Incremental frame extractor for a streaming receive path: feed
/// arbitrary chunks, get back whole payloads as they complete.
public final class FrameReader {

    private var buffer = Data()

    public init() {}

    public func feed(_ data: Data) throws -> [Data] {
        buffer.append(data)
        var frames: [Data] = []
        while buffer.count >= 4 {
            let length = buffer.prefix(4).reduce(UInt64(0)) { ($0 << 8) | UInt64($1) }
            guard length <= UInt64(FrameCodec.maxFrameBytes) else {
                throw FrameError.tooLarge(declared: length)
            }
            let total = 4 + Int(length)
            guard buffer.count >= total else { break }
            frames.append(Data(buffer.dropFirst(4).prefix(Int(length))))
            buffer = Data(buffer.dropFirst(total))
        }
        return frames
    }

    /// Returns the first completed payload and all bytes that must be replayed
    /// by the next protocol layer, preserving coalesced complete frames and any
    /// partial trailing frame already buffered by this reader.
    public func feedUntilFirst(_ data: Data) throws -> (frame: Data, leftover: Data)? {
        let frames = try feed(data)
        guard let first = frames.first else { return nil }
        var leftover = Data()
        for frame in frames.dropFirst() {
            leftover.append(try FrameCodec.encode(frame))
        }
        leftover.append(remainder())
        return (first, leftover)
    }

    /// Hands off any partially-buffered bytes and resets — used when one
    /// layer (handshake) stops reading and another (session) takes over.
    public func remainder() -> Data {
        let leftover = buffer
        buffer = Data()
        return leftover
    }
}
