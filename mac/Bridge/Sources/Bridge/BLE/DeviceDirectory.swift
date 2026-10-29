import Foundation

/// One phone the Mac can see right now.
struct DiscoveredDevice: Identifiable, Equatable, Sendable {
    let id: UUID
    var name: String
    var rssi: Int
    var lastSeen: Date
}

/// The devices an unpaired Mac is choosing between (BR-5).
///
/// Every Bridge install advertises the same service UUID, so a scan in a shared space
/// returns several phones and the Mac must not connect to whichever it saw first.
///
/// Entries age out on a last-seen timer rather than on the first missed advertisement:
/// BLE advertising is periodic, so a device that is sitting right there still goes quiet
/// for stretches, and dropping it immediately would make the list flicker.
struct DeviceDirectory: Equatable {

    /// Comfortably longer than a typical advertising interval, short enough that a phone
    /// carried out of the room leaves the list while the user is still looking at it.
    static let staleAfter: TimeInterval = 10

    private(set) var devices: [DiscoveredDevice] = []

    /// Adds or refreshes a device. Strongest signal first, so the phone on the desk is
    /// at the top rather than one three rooms away.
    mutating func record(id: UUID, name: String, rssi: Int, now: Date = Date()) {
        if let index = devices.firstIndex(where: { $0.id == id }) {
            devices[index].name = name
            devices[index].rssi = rssi
            devices[index].lastSeen = now
        } else {
            devices.append(DiscoveredDevice(id: id, name: name, rssi: rssi, lastSeen: now))
        }
        prune(now: now)
    }

    mutating func prune(now: Date = Date()) {
        devices.removeAll { now.timeIntervalSince($0.lastSeen) > Self.staleAfter }
        devices.sort { lhs, rhs in
            lhs.rssi == rhs.rssi ? lhs.name < rhs.name : lhs.rssi > rhs.rssi
        }
    }

    mutating func removeAll() {
        devices.removeAll()
    }
}
