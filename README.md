# Train Arrivals

A small Android app showing live departures for one London station and
direction, using the free [TfL Unified API](https://api.tfl.gov.uk).

On first launch it asks you to pick:

1. **A line** – Tube lines, Elizabeth line, DLR, London Overground lines or trams.
2. **Your station** on that line (searchable list).
3. **Which trains** – tick the destinations you care about (usually the end
   of the line in your direction), or leave all unticked for every train.

Your choice is stored only on the phone. Tap **Change** on the main screen to
pick again.

The main screen lists the next matching trains with departure time, a
countdown, platform and current location. It refreshes every 30 seconds while
open.

For Elizabeth line and Overground stations it uses TfL's departure board
(`ArrivalDepartures`), which shows cancelled and non-stopping trains (struck
through in red) and delays (timetabled vs expected time). It also checks TfL's
arrivals feed, and a small ⚠ note marks any train where the two feeds disagree.
Tube, DLR and tram stations use the arrivals feed only.

No third-party libraries – just the Android platform.

## Getting the APK
Every push runs the **Build APK** workflow (Actions tab). Open the latest run
and download the `TrainArrivals-apk` artifact, unzip it on your phone and
install `app-debug.apk` (allow "install unknown apps" when asked).

Or open the project in Android Studio and press Run.

## TfL rate limit and API key
Without a key, TfL allows only a small number of requests, shared by everyone
on the same network address. If TfL says "too many requests", the app waits
as long as TfL asks before trying again and keeps showing the last list.

For a much higher limit, get a free key at https://api-portal.tfl.gov.uk and
enter it via the "filtered out / details" line → **API key**. The key is stored
only on the phone, never in this repository.
