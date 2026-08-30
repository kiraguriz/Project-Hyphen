/// Stable automation and assistive-technology identifiers for v1's security-
/// sensitive pairing and diagnostics controls.
enum HyphenAccessibilityID {
    static let pairingQRCode = "hyphen.pairing.qr"
    static let pairingManualPayload = "hyphen.pairing.manual-payload"
    static let pairingSAS = "hyphen.pairing.sas"
    static let pairingConfirm = "hyphen.pairing.confirm"
    static let pairingReject = "hyphen.pairing.reject"
    static let diagnosticsTraceIDs = "hyphen.diagnostics.trace-ids"
    static let diagnosticsPreview = "hyphen.diagnostics.preview"
    static let diagnosticsExport = "hyphen.diagnostics.export"
    static let diagnosticsDelete = "hyphen.diagnostics.delete"

    static let all = [
        pairingQRCode,
        pairingManualPayload,
        pairingSAS,
        pairingConfirm,
        pairingReject,
        diagnosticsTraceIDs,
        diagnosticsPreview,
        diagnosticsExport,
        diagnosticsDelete,
    ]
}
