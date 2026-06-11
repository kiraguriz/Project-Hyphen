import XCTest
@testable import HyphenCore

/// Reproduces every case in the normative vector file
/// `protocol/test-vectors/pairing/sas-vectors.json` (HYP-M2-004) — the
/// Swift implementation is conformant only if all of them match.
final class PairingTranscriptTests: XCTestCase {

    private struct VectorFile: Decodable {
        let cases: [Vector]
        let tamperCases: [Vector]
    }

    private struct Vector: Decodable {
        let name: String
        let nonceHex: String
        let macSpkiFpHex: String
        let androidSpkiFpHex: String
        let protocolVersion: String
        let expectedTranscriptHashHex: String
        let expectedSas: String
    }

    private func loadVectors() throws -> VectorFile {
        // apps/macos/Tests/HyphenCoreTests/<file> → repo root is 5 up.
        var url = URL(fileURLWithPath: #filePath)
        for _ in 0..<5 { url.deleteLastPathComponent() }
        url.appendPathComponent("protocol/test-vectors/pairing/sas-vectors.json")
        return try JSONDecoder().decode(VectorFile.self, from: Data(contentsOf: url))
    }

    private func transcript(for vector: Vector) throws -> PairingTranscript {
        try XCTUnwrap(
            PairingTranscript(
                nonce: XCTUnwrap(Data(hyphenHexString: vector.nonceHex)),
                macSpkiFingerprint: XCTUnwrap(Data(hyphenHexString: vector.macSpkiFpHex)),
                androidSpkiFingerprint: XCTUnwrap(Data(hyphenHexString: vector.androidSpkiFpHex)),
                protocolVersion: vector.protocolVersion
            ),
            "vector \(vector.name) did not build"
        )
    }

    func testReproducesEveryNormativeVector() throws {
        let vectors = try loadVectors()
        XCTAssertEqual(vectors.cases.count, 5, "vector file shrank — investigate")
        for vector in vectors.cases {
            let result = try transcript(for: vector)
            XCTAssertEqual(
                result.hash.hyphenHexString, vector.expectedTranscriptHashHex,
                "transcript hash mismatch in \(vector.name)"
            )
            XCTAssertEqual(result.sas, vector.expectedSas, "SAS mismatch in \(vector.name)")
            XCTAssertEqual(result.sas.count, 6, "SAS must render 6 digits in \(vector.name)")
        }
    }

    func testTamperCasesAreDetectedAsMismatch() throws {
        for vector in try loadVectors().tamperCases {
            let result = try transcript(for: vector)
            XCTAssertNotEqual(
                result.sas, vector.expectedSas,
                "verifier self-test \(vector.name): wrong expectation must mismatch"
            )
        }
    }

    func testTranscriptLayoutIsLabelNonceFpsVersion() throws {
        let nonce = Data(repeating: 0x01, count: 16)
        let mac = Data(repeating: 0x02, count: 32)
        let android = Data(repeating: 0x03, count: 32)
        let transcript = try XCTUnwrap(
            PairingTranscript(nonce: nonce, macSpkiFingerprint: mac, androidSpkiFingerprint: android, protocolVersion: "hyphen/0.3")
        )
        XCTAssertEqual(
            transcript.transcriptData,
            Data(PairingTranscript.label.utf8) + nonce + mac + android + Data("hyphen/0.3".utf8)
        )
    }

    func testInvalidFieldLengthsAreRejected() {
        let nonce = Data(count: 16)
        let fp = Data(count: 32)
        XCTAssertNil(PairingTranscript(nonce: Data(count: 15), macSpkiFingerprint: fp, androidSpkiFingerprint: fp, protocolVersion: "hyphen/0.3"))
        XCTAssertNil(PairingTranscript(nonce: nonce, macSpkiFingerprint: Data(count: 31), androidSpkiFingerprint: fp, protocolVersion: "hyphen/0.3"))
        XCTAssertNil(PairingTranscript(nonce: nonce, macSpkiFingerprint: fp, androidSpkiFingerprint: Data(count: 33), protocolVersion: "hyphen/0.3"))
        XCTAssertNil(PairingTranscript(nonce: nonce, macSpkiFingerprint: fp, androidSpkiFingerprint: fp, protocolVersion: ""))
    }
}
