import XCTest
@testable import HyphenCore

final class PairingQRPayloadTests: XCTestCase {

    // 0xFB/0xFF force '+' and '/' in standard base64 — these must come out
    // as '-' and '_' in base64url.
    private let fingerprint = Data([0xFB, 0xFF] + Array(repeating: UInt8(0x42), count: 30))
    private let nonce = Data((0..<16).map { UInt8($0) })

    private func payload(deviceName: String? = nil) -> PairingQRPayload {
        PairingQRPayload(
            host: "192.168.1.20",
            port: 48273,
            spkiFingerprint: fingerprint,
            nonce: nonce,
            deviceName: deviceName
        )!
    }

    private func queryItems(of uri: String) -> [String: String] {
        let query = uri.split(separator: "?", maxSplits: 1)[1]
        var items: [String: String] = [:]
        for pair in query.split(separator: "&") {
            let kv = pair.split(separator: "=", maxSplits: 1)
            items[String(kv[0])] = kv.count > 1 ? String(kv[1]) : ""
        }
        return items
    }

    private func base64urlDecode(_ value: String) -> Data? {
        var standard = value
            .replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
        while standard.count % 4 != 0 { standard += "=" }
        return Data(base64Encoded: standard)
    }

    func testUriCarriesAllRequiredFieldsRoundTrip() {
        let uri = payload().uriString
        XCTAssertTrue(uri.hasPrefix("hyphen://pair?"))

        let items = queryItems(of: uri)
        XCTAssertEqual(items["v"], "0")
        XCTAssertEqual(items["ep"], "192.168.1.20:48273")
        XCTAssertEqual(base64urlDecode(items["fp"] ?? ""), fingerprint)
        XCTAssertEqual(base64urlDecode(items["n"] ?? ""), nonce)
        XCTAssertNil(items["dn"])
    }

    func testBinaryFieldsAreUnpaddedBase64URL() {
        let items = queryItems(of: payload().uriString)
        for field in [items["fp"]!, items["n"]!] {
            XCTAssertFalse(field.contains("="), "padding must be stripped")
            XCTAssertFalse(field.contains("+"), "must use the url alphabet")
            XCTAssertFalse(field.contains("/"), "must use the url alphabet")
        }
        // 32 bytes → 43 chars, 16 bytes → 22 chars when unpadded.
        XCTAssertEqual(items["fp"]!.count, 43)
        XCTAssertEqual(items["n"]!.count, 22)
    }

    func testDeviceNamePercentEncodingRoundTrips() {
        let name = "Haitian's Mac & 测试 +1"
        let items = queryItems(of: payload(deviceName: name).uriString)
        let encoded = items["dn"]!
        // No raw separators or '+' (Java URLDecoder reads '+' as space).
        XCTAssertFalse(encoded.contains("&"))
        XCTAssertFalse(encoded.contains("="))
        XCTAssertFalse(encoded.contains("+"))
        XCTAssertTrue(encoded.contains("%20"), "spaces must be %20")
        XCTAssertEqual(encoded.removingPercentEncoding, name)
    }

    func testInvalidInputsAreRejected() {
        XCTAssertNil(PairingQRPayload(host: "h", port: 1, spkiFingerprint: Data(count: 31), nonce: nonce))
        XCTAssertNil(PairingQRPayload(host: "h", port: 1, spkiFingerprint: fingerprint, nonce: Data(count: 15)))
        XCTAssertNil(PairingQRPayload(host: "", port: 1, spkiFingerprint: fingerprint, nonce: nonce))
        XCTAssertNil(PairingQRPayload(host: "fe80::1", port: 1, spkiFingerprint: fingerprint, nonce: nonce))
        XCTAssertNil(PairingQRPayload(host: "h", port: 0, spkiFingerprint: fingerprint, nonce: nonce))
    }

    func testNonceIsFreshAndSized() {
        let a = PairingNonce.random()
        let b = PairingNonce.random()
        XCTAssertEqual(a.count, 16)
        XCTAssertEqual(b.count, 16)
        XCTAssertNotEqual(a, b)
    }

    func testQRCodeRendersForTypicalPayload() throws {
        let image = try XCTUnwrap(
            QRCodeRenderer.image(for: payload(deviceName: "Haitian's Mac").uriString)
        )
        XCTAssertGreaterThan(image.width, 0)
        XCTAssertEqual(image.width, image.height)
    }
}
