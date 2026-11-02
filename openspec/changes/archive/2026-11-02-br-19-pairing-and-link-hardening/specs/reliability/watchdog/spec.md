## MODIFIED Requirements

### Requirement: Heartbeat ping at regular interval
Both sides send `bridge.ping` packets at a regular interval to prove the link is alive, and both
sides answer a received `bridge.ping` with a `bridge.ping` reply.

A reply SHALL carry `replyTo` set to the `id` of the ping it answers, and SHALL NOT itself be
answered — the exchange is one round trip, not a loop. A side that receives a reply treats it as
any other received packet for last-seen purposes.

Answering is required on both platforms. A side that heartbeats but never answers leaves the
peer unable to distinguish a live link from a dead one inside any window shorter than the
heartbeat interval, which is the gap a link probe has to work in.

#### Scenario: Idle link heartbeat
- **WHEN** no application packet has been exchanged for 30 seconds
- **THEN** the Mac sends a `bridge.ping` to the phone

#### Scenario: Ping response
- **WHEN** one side receives a `bridge.ping` that is not itself a reply
- **THEN** it updates its last-seen timestamp for the peer **and** sends a `bridge.ping` back
  carrying `replyTo` set to the received packet's `id`

#### Scenario: A reply is not answered again
- **WHEN** a `bridge.ping` carrying `replyTo` arrives
- **THEN** the last-seen timestamp is updated and no further packet is sent

#### Scenario: Both platforms answer
- **WHEN** either the Mac or the phone receives a ping
- **THEN** it replies, so a probe from either side completes within one round trip

#### Scenario: Active link suppresses heartbeat
- **WHEN** application packets (notifications, clipboard, etc.) are flowing
- **THEN** no heartbeat ping is sent (any received packet resets the heartbeat timer)
