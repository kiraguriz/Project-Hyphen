import Foundation

/// Minimal DER encoder — exactly the constructs a self-signed X.509 v3
/// certificate needs (HYP-M2-007). Encoding only; parsing stays with
/// Security.framework. Clean-room from the ASN.1 DER rules (ITU-T X.690).
enum DER {

    static func sequence(_ contents: Data...) -> Data {
        tagged(0x30, contents.reduce(Data(), +))
    }

    static func set(_ contents: Data...) -> Data {
        tagged(0x31, contents.reduce(Data(), +))
    }

    /// EXPLICIT context-specific constructed tag, e.g. `[0]` for version.
    static func contextTag(_ number: UInt8, _ contents: Data) -> Data {
        tagged(0xA0 | number, contents)
    }

    /// INTEGER from raw big-endian magnitude bytes (positive): strips
    /// redundant leading zeros, then left-pads one 0x00 if the high bit
    /// is set, per DER minimal two's complement.
    static func integer(_ magnitude: Data) -> Data {
        var bytes = Data(magnitude)
        while bytes.count > 1, bytes.first == 0x00, bytes[bytes.index(after: bytes.startIndex)] & 0x80 == 0 {
            bytes.removeFirst()
        }
        if bytes.isEmpty { bytes = Data([0x00]) }
        if bytes.first! & 0x80 != 0 { bytes.insert(0x00, at: bytes.startIndex) }
        return tagged(0x02, bytes)
    }

    static func integer(_ value: UInt8) -> Data {
        integer(Data([value]))
    }

    /// BIT STRING with zero unused bits (all X.509 uses here are byte-aligned).
    static func bitString(_ contents: Data) -> Data {
        tagged(0x03, Data([0x00]) + contents)
    }

    /// OBJECT IDENTIFIER from dotted notation, e.g. "1.2.840.10045.2.1".
    static func objectIdentifier(_ dotted: String) -> Data {
        let parts = dotted.split(separator: ".").map { UInt($0)! }
        precondition(parts.count >= 2, "OID needs at least two arcs")
        var body = Data([UInt8(parts[0] * 40 + parts[1])])
        for arc in parts.dropFirst(2) {
            body.append(base128(arc))
        }
        return tagged(0x06, body)
    }

    static func utf8String(_ value: String) -> Data {
        tagged(0x0C, Data(value.utf8))
    }

    /// UTCTime (YYMMDDHHMMSSZ); valid for dates in 1950–2049.
    static func utcTime(_ date: Date) -> Data {
        let formatter = DateFormatter()
        formatter.dateFormat = "yyMMddHHmmss'Z'"
        formatter.timeZone = TimeZone(identifier: "UTC")
        formatter.locale = Locale(identifier: "en_US_POSIX")
        return tagged(0x17, Data(formatter.string(from: date).utf8))
    }

    static func tagged(_ tag: UInt8, _ contents: Data) -> Data {
        Data([tag]) + length(contents.count) + contents
    }

    private static func length(_ count: Int) -> Data {
        if count < 0x80 { return Data([UInt8(count)]) }
        var bytes: [UInt8] = []
        var remaining = count
        while remaining > 0 {
            bytes.insert(UInt8(remaining & 0xFF), at: 0)
            remaining >>= 8
        }
        return Data([0x80 | UInt8(bytes.count)] + bytes)
    }

    private static func base128(_ value: UInt) -> Data {
        var groups: [UInt8] = [UInt8(value & 0x7F)]
        var remaining = value >> 7
        while remaining > 0 {
            groups.insert(UInt8(remaining & 0x7F) | 0x80, at: 0)
            remaining >>= 7
        }
        return Data(groups)
    }
}
