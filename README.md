# elco-poller

Read-only poller for an Elco heat pump on Remocon-NET. Writes one CSV file per day.

**It only reads.** It logs in like the Remocon web page and sends GET requests only; it never changes a setting.

## Run

```bash
cp elco.properties.example elco.properties   # put your Remocon-NET email + password in
chmod 600 elco.properties                    # it contains your password
java -jar elco-poller.jar --once             # one poll, then exit (good first test)
java -jar elco-poller.jar                    # runs forever (Ctrl+C to stop)
java -jar elco-poller.jar /path/to/elco.properties
java -jar elco-poller.jar --no-web            # poller only, no dashboard
```

## Dashboard

When the poller runs, it also serves a small dashboard at **http://localhost:8083/**. It runs in the same JVM and has no extra dependencies.

What it shows:
- **Today by default.** It refreshes every minute and rolls over to the new day at midnight.
- **Other days:** pick one with ‹ › or the date field; **Today** jumps back.
- **Tiles** with the last good reading: time (hh:mm), heat pump on/off, outdoor, indoor, hot water, flow (+ setpoint), return, flow rate, heat output, heat produced that day.
- **Temperatures chart:** hot water, indoor, outdoor and flow, plus a strip showing when the heat pump was on. Hover for exact values.
- **Heat produced per hour** (kWh) with the day total.
- **All readings** as a table.

Access:
- `web.bind=127.0.0.1` (the default) limits it to this Mac.
- With `0.0.0.0` it's reachable from your network. It has no login, so only do that on a trusted network.

Requires Java 17+. Rebuild with `mvn package` (jar lands in `target/elco-poller.jar`).

## Polling

- Every **115 s** while the compressor is running, every **600 s** while it is off.
- Each poll is a single request: the 10 BSB data points listed below, read from the "elco sx 10" gateway.
- On "429 Requests are blocked" it waits 2 minutes and retries. If the session expires (HTTP 403/405), it logs in again.

## Output: `data/elco_YYYY-MM-DD.csv` (new file every day)

| Column | Source | Unit |
|---|---|---|
| `timestamp` | local time | |
| `hp_on` | BSB 8400, compressor 1 | 1 / 0 |
| `outdoor_temp_c` | BSB 8700 | °C |
| `indoor_temp_c` | BSB 8740, room temperature 1 | °C |
| `hot_water_temp_c` | BSB 8830, DHW temperature | °C |
| `flow_temp_c` | BSB 8412, heat pump flow | °C |
| `flow_setpoint_c` | BSB 8411, heat pump flow setpoint | °C |
| `return_temp_c` | BSB 8410, heat pump return | °C |
| `evaporator_temp_c` | BSB 8475, evaporator temperature | °C |
| `suction_gas_temp_c` | BSB 8434, suction gas (entering compressor) | °C |
| `hot_gas_temp_c` | BSB 8415, hot gas (leaving compressor) | °C |
| `liquid_temp_c` | BSB 8420, refrigerant liquid (after condenser) | °C |
| `compressor_modulation_pct` | BSB 8413, compressor speed (modulation) | % |
| `dhw_valve` | BSB 8820, hot water valve Q3: 1 = hot water, 0 = heating | |
| `flow_rate_l_min` | BSB 8460, heat pump water flow | l/min |
| `heat_output_kw` | BSB 8395, heat output reported by the controller | kW |
| `heat_kwh_interval` | heat since the previous poll: average kW × time | kWh |
| `heat_kwh_today` | running total for the day; resets at midnight and resumes after a restart | kWh |
| `poll_problems` | errors during this poll, empty if all went well | |

The dashboard also shows the day's min–max for outdoor, indoor and hot water, and the flow temperature's min–max over the last hour.

How the kWh columns are computed:
- Gaps longer than 15 minutes (app stopped, network down) are not integrated, so no energy is guessed for them.
- As a cross-check, heat can also be computed as `flow_rate_l_min / 60 × 4.19 × (flow − return)` in kW.

## Electricity and COP

On the first poll after each even hour (00:00, 02:00 … 22:00) the poller fetches the electricity consumption from the "strom" gateway:
- The data comes in 2‑hour blocks; Elco calls these figures estimates.
- The server publishes the last 48 h. On start, the poller backfills those 48 h.
- Each new series is checked against the previous one. If the newest block isn't published yet, it tries again on the next poll, so a late server never puts a value on the wrong block.

Blocks are appended once to `data/elco_power_YYYY-MM-DD.csv` (`slot_start, slot_end, electricity_kwh`), filed under the date of the block start.

The dashboard shows heat produced and electricity used per 2 h block, with the **COP** (heat ÷ electricity) above each block, plus a **COP for the day** at the top:
- Heat includes hot water (the heat pump's total output), and so does the electricity. That makes the COP a total COP.
- A block counts only when at least 90 % of its 2 h were measured by the poller. Otherwise missing heat readings would make the COP look too low.

## Adding values

Add a row to `src/main/resources/bsb_points.csv`, then run `mvn package`. The address is `((cmd >> 24) << 16) | (cmd & 0xFFFF)`, where `cmd` is the BSB-LAN command id of the parameter.

If Elco changes the login page (`/R2/Account/Login`: anti-forgery token + JSON post), the login is the part most likely to need adjusting.
