# Fletching Profit

Standalone EpicBot script for profitable Old School RuneScape Fletching.

## What It Does

- Selects the most profitable unlocked bow pipeline from current EpicBot pricing data.
- Buys missing logs and bow strings at the Grand Exchange.
- Cuts logs into unstrung bows.
- Strings unstrung bows into finished bows.
- Sells finished bows, then refreshes the profit selector for the next cycle.

## Build

```powershell
.\gradlew.bat :fletching-banker:build
```

The EpicBot Gradle plugin copies the compiled classes to the local EpicBot scripts directory configured on the machine.

## Run

Open EpicBot, refresh local scripts, and select **Fletching Profit**.

For the first test, start near a bank or the Grand Exchange with coins available. Watch the creation-menu step closely, because the `Make` widget can vary by client layout.
