import Foundation

/// ULID generation (HYP-M2-012): 48-bit unix-ms timestamp + 80 random
/// bits, Crockford base32, 26 chars — the `messageId` format pinned by
/// `envelope.schema.json`. Clean-room from the ULID spec description;
/// twin of the Android `Ulid`.
public enum Ulid {

    private static let alphabet = Array("0123456789ABCDEFGHJKMNPQRSTVWXYZ")

    public static func generate(nowMs: UInt64 = UInt64(Date().timeIntervalSince1970 * 1000)) -> String {
        var chars = [Character](repeating: "0", count: 26)
        var time = nowMs
        for i in stride(from: 9, through: 0, by: -1) {
            chars[i] = alphabet[Int(time & 31)]
            time >>= 5
        }
        let randomBytes = (0..<10).map { _ in UInt8.random(in: .min ... .max) }
        var buffer: UInt64 = 0
        var bits = 0
        var index = 10
        for byte in randomBytes {
            buffer = (buffer << 8) | UInt64(byte)
            bits += 8
            while bits >= 5 {
                bits -= 5
                chars[index] = alphabet[Int((buffer >> UInt64(bits)) & 31)]
                index += 1
            }
        }
        return String(chars)
    }

    public static func isValid(_ value: String) -> Bool {
        value.range(of: "^[0-9A-HJKMNP-TV-Z]{26}$", options: .regularExpression) != nil
    }
}
