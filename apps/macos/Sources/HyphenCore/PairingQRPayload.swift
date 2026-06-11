import CoreImage
import Foundation
import Security

/// The `hyphen://pair` QR payload the Mac displays (HYP-M2-009, protocol
/// v0 §5.1). Field encodings are pinned by tests because the Android
/// parser (HYP-M1-006/M2-010) must accept exactly this output:
/// base64url WITHOUT padding for binary fields, RFC 3986 unreserved-only
/// percent-encoding for the device name (never a raw `+`, which Java's
/// URLDecoder would turn into a space).
public struct PairingQRPayload: Equatable {

    /// Pairing payload version (the `v` parameter) — distinct from the
    /// protocol identifier string bound into the SAS transcript.
    public static let version = 0
    public static let fingerprintLength = 32
    public static let nonceLength = 16

    public let host: String
    public let port: UInt16
    /// This device's SPKI fingerprint (32 bytes) — pre-shares the pin.
    public let spkiFingerprint: Data
    /// Fresh per-pairing-session nonce (16 bytes), bound into the SAS
    /// transcript so a replayed QR can't be confirmed later.
    public let nonce: Data
    public let deviceName: String?

    public init?(
        host: String,
        port: UInt16,
        spkiFingerprint: Data,
        nonce: Data,
        deviceName: String? = nil
    ) {
        guard spkiFingerprint.count == Self.fingerprintLength,
              nonce.count == Self.nonceLength,
              !host.isEmpty,
              host.count <= 253,
              !host.contains(":"), // IPv6 literals are an M2 follow-up, like the Android parser
              !host.contains("/"),
              host.rangeOfCharacter(from: .whitespacesAndNewlines) == nil,
              port >= 1
        else { return nil }
        self.host = host
        self.port = port
        self.spkiFingerprint = spkiFingerprint
        self.nonce = nonce
        self.deviceName = deviceName
    }

    public var uriString: String {
        var uri = "hyphen://pair?v=\(Self.version)"
            + "&ep=\(host):\(port)"
            + "&fp=\(spkiFingerprint.hyphenBase64URL)"
            + "&n=\(nonce.hyphenBase64URL)"
        if let deviceName,
           let encoded = deviceName.addingPercentEncoding(withAllowedCharacters: Self.unreserved) {
            uri += "&dn=\(encoded)"
        }
        return uri
    }

    private static let unreserved = CharacterSet(
        charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
    )
}

public enum PairingNonce {
    /// 16 crypto-grade random bytes; fresh per pairing session.
    public static func random() -> Data {
        var bytes = Data(count: PairingQRPayload.nonceLength)
        let status = bytes.withUnsafeMutableBytes {
            SecRandomCopyBytes(kSecRandomDefault, PairingQRPayload.nonceLength, $0.baseAddress!)
        }
        precondition(status == errSecSuccess, "SecRandomCopyBytes failed: \(status)")
        return bytes
    }
}

public enum QRCodeRenderer {
    /// Renders via CoreImage's built-in generator (no dependency).
    /// Error-correction M; `scale` multiplies the module size.
    public static func image(for string: String, scale: CGFloat = 8) -> CGImage? {
        guard let filter = CIFilter(name: "CIQRCodeGenerator") else { return nil }
        filter.setValue(Data(string.utf8), forKey: "inputMessage")
        filter.setValue("M", forKey: "inputCorrectionLevel")
        guard let output = filter.outputImage else { return nil }
        let scaled = output.transformed(by: CGAffineTransform(scaleX: scale, y: scale))
        return CIContext().createCGImage(scaled, from: scaled.extent)
    }
}

extension Data {
    /// base64url (RFC 4648 §5) without padding.
    var hyphenBase64URL: String {
        base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }
}
