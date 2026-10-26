import Foundation

/// How many of the phone's apps are mirroring, as the phone last reported it.
///
/// The Mac cannot work this out for itself: "no apps are allowed" and "the allowed
/// apps happen to be quiet" look identical from here, and that ambiguity is what made
/// a silently-empty allow-list survive so long. So the phone reports it and the Mac
/// only ever displays what it was told — `nil` until a `bridge.notification.filter`
/// packet arrives, never a guess.
struct MirroringState: Equatable, Sendable {
    let allowed: Int
    let seen: Int
    let muted: Bool

    /// Paused outranks the count: an allowed app is not mirroring while master mute is on.
    var summary: String {
        if muted { return "Mirroring paused" }
        if allowed == 0 { return "No apps mirroring" }
        return allowed == 1 ? "Mirroring 1 app" : "Mirroring \(allowed) apps"
    }

    /// What to show before the phone has reported anything this connection.
    static let unknownSummary = "Mirroring: unknown"
}
