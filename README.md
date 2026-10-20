# Train Arrivals

A small Android app showing live departures for one London station and
direction, using the free [TfL Unified API](https://api.tfl.gov.uk).

On first launch it asks you to pick:

1. **A line** – Tube lines, Elizabeth line, DLR, London Overground lines or trams.
2. **Your station** on that line (searchable list).
3. **Which trains** – either by **final destination** (tick the ends of the
   line you want) or **calling at** (tick stations the train must stop at
   after yours, whatever its final destination). Leave all unticked for every
   train. "Calling at" uses TfL's timetabled stopping patterns, fetched once a
   day per line.

Your choice is stored only on the phone. Tap **Change** on the main screen to
pick again.

**Starred setups:** tap **☆ Star this** under the header to keep the current
setup. Starred setups appear as chips in that row: tap one to switch,
long-press to remove it.

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

## Data sources and keys
Tap **Sources** on the main screen for the full explanation and to enter keys.
Both keys are optional, free, and stored only on the phone, never in this
repository.

- **TfL** (always used). Without a key TfL allows only a few requests,
  shared by everyone on the same network address. If TfL says "too many
  requests", the app waits as long as TfL asks and keeps showing the last
  list. A free key from https://api-portal.tfl.gov.uk lifts the limit.
- **National Rail** (optional, Elizabeth line and Overground only). The Live
  Departure Board from the Rail Data Marketplace (https://raildata.org.uk),
  plus your station's 3-letter CRS code. It's compared with TfL, and a ⚠ note
  marks trains where they disagree (e.g. cancelled on one, running on the
  other). If TfL's departure board fails, National Rail is shown instead.

The "filtered out — details ›" line under the status opens a full report of
every request, error and entry that wasn't shown.
