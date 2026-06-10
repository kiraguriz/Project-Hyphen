import XCTest
@testable import HyphenDiscovery

/// Real-mDNS loopback proof for HYP-M1-011: advertise on this machine,
/// browse it back on this machine. Equivalent to checking with
/// `dns-sd -B _hyphen._tcp local.` while the app advertises.
final class BonjourLoopbackTests: XCTestCase {

    func testAdvertisedServiceIsBrowsableOnThisMachine() {
        let serviceName = "hyphen-test-\(UUID().uuidString.prefix(8))"

        let ready = expectation(description: "advertiser reaches .advertising")
        ready.assertForOverFulfill = false
        var advertisedPort: UInt16 = 0
        let advertiser = BonjourAdvertiser { state in
            if case let .advertising(port) = state {
                advertisedPort = port
                ready.fulfill()
            }
        }

        let found = expectation(description: "browser sees the service")
        found.assertForOverFulfill = false
        let browser = BonjourBrowser { names in
            if names.contains(serviceName) {
                found.fulfill()
            }
        }

        advertiser.start(deviceName: serviceName)
        wait(for: [ready], timeout: 10)
        XCTAssertGreaterThan(advertisedPort, 0, "listener must bind a real port")

        browser.start()
        wait(for: [found], timeout: 10)

        browser.stop()
        advertiser.stop()
    }

    func testStopTransitionsToStopped() {
        let stopped = expectation(description: "stopped state reported")
        stopped.assertForOverFulfill = false
        let advertiser = BonjourAdvertiser { state in
            if state == .stopped {
                stopped.fulfill()
            }
        }
        advertiser.start(deviceName: "hyphen-test-stop")
        advertiser.stop()
        wait(for: [stopped], timeout: 10)
    }
}
