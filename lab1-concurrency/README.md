# Laboratory Work 1: Concurrency in Practice

**Network Programming** · Macari Chiril-Adam, Carazanu Augustin · FAF-243

This is one Java program (`Lab1`). The command-line arguments select every task and configuration.
It counts the negative, positive and zero numbers in `numbers.txt` (50 000 000 random integers
in [-1 000 000, 1 000 000]) in several ways, measures the time and peak memory, checks the
result against the true counts, and appends one line per run to `results.csv`.

## Requirements

- **JDK 17 or newer** (tested with OpenJDK 21). On Windows: `winget install EclipseAdoptium.Temurin.21.JDK`,
  then open a new PowerShell window. The scripts find the newest JDK by themselves
  (`scripts\jdk.ps1`), even if an old Java 8 comes first in PATH.
- About **1.2 GB free disk space** (`numbers.txt` ≈ 370 MB, plus the 8 part files ≈ 370 MB).
- Task 2: a USB flash drive with ≈ 400 MB free.
- Optional, for the charts: Python 3 with `pip install matplotlib`.

## Project layout

```
src/
  Lab1.java        entry point: parses the command, times the task, prints and saves the result
  DataGen.java     gen (numbers.txt + numbers.txt.counts) and split (parts/part1..8.txt)
  SignParser.java  streaming byte parser: one integer per line, \n or \r\n
  Counts.java      negative / positive / zero counters
  Memory.java      peak memory: process (PeakWorkingSet64 on Windows, VmHWM on Linux) and Java heap
  Task1a.java      file divided into byte ranges, one per thread
  Task1b.java      three ways to count an int[]: no sync / lock / per-thread counters
  Task1c.java      one reader thread -> ArrayBlockingQueue(100 batches of 10 000 lines) -> workers
  Task2.java       8 files dealt round-robin to the threads, each read with one readAllBytes()
scripts/
  build.ps1        compile into out/
  hardware.ps1     hardware description for the report -> hardware.txt
  run_task1.ps1    every configuration of 1a, 1b, 1c -> results.csv
  run_task2.ps1    Task 2 on the USB drive, with eject/replug prompts
  run_task1.sh     the same as run_task1.ps1, for Linux/macOS
  make_charts.py   charts (PNG) + summary.md from results.csv
```

## Build

Windows (PowerShell, from the project folder):

```powershell
powershell -ExecutionPolicy Bypass -File scripts\build.ps1
```

Any OS:

```
javac -d out src/*.java
```

## Run each task by hand

All commands are run from the project folder. `numbers.txt` is looked for in the current folder
(change it with `--file`).

| What | Command |
|---|---|
| Generate the data (once) | `java -cp out Lab1 gen` (seed 42; `--count`, `--seed` to change) |
| Split into 8 files (once) | `java -cp out Lab1 split` → `parts\part1.txt … part8.txt` |
| Task 1a, N threads | `java -cp out Lab1 1a --threads N` |
| Task 1b, version V | `java -cp out Lab1 1b --version V` (1 = no sync, 2 = lock, 3 = per-thread; threads default to the number of logical processors) |
| Task 1c, N workers | `java -cp out Lab1 1c --workers N` |
| Task 1c, other queue setup | `java -cp out Lab1 1c --workers N --batch 1` or `--capacity 0` (0 means no size limit) |
| Task 2, N threads | `java -cp out Lab1 2 --threads N --dir E:\parts` |
| JVM / processor info | `java -cp out Lab1 info` |

Common options: `--csv results.csv` (where to append), `--run K` (run number stored in the CSV).

Each run prints, for example:

```
Task 1a  [split-file]  threads=8
  negative=25,001,107  positive=24,998,868  zero=25  total=50,000,000
  expected negative=25,001,107  positive=24,998,868  zero=25  total=50,000,000
  result: CORRECT
  time: 412.3 ms
  peak memory (process): 61.4 MB   peak Java heap: 19.0 MB
```

## Run all the measurements (Windows)

1. Plug the laptop into the charger, set the power mode to **Best performance**, and close other programs.
2. Hardware description:
   `powershell -ExecutionPolicy Bypass -File scripts\hardware.ps1`
3. Task 1 (generates the data the first time; about 5–10 minutes):
   `powershell -ExecutionPolicy Bypass -File scripts\run_task1.ps1 -Repeats 3 -Extras`
4. Task 2, with the USB drive as `E:` (`-Copy` copies the files to the drive the first time):
   `powershell -ExecutionPolicy Bypass -File scripts\run_task2.ps1 -Drive E: -Copy -AlsoLocal`
   Before each of the 3 runs the script waits while you **eject → unplug → plug back in** the drive.
5. Charts: `python scripts\make_charts.py results.csv --physical-cores <your physical cores>`
   → `charts\*.png` and `charts\summary.md`.

## How the measurements are taken

- **Time** is measured with `System.nanoTime()` around the task only: thread creation, reading,
  parsing and joining. JVM start-up is not included. In Task 1b the array is loaded first and that
  part is not measured.
- **Peak memory (process)** is the most physical memory the process ever used (Windows
  `PeakWorkingSet64`, Linux `VmHWM`). It includes the heap, the thread stacks and the JVM itself.
  **Peak Java heap** is the highest heap usage the JVM recorded.
- Every configuration runs in a **new JVM**, so the peaks belong to that configuration only.
- `run_task1.ps1` reads `numbers.txt` once before measuring. After that the file is in the OS file
  cache, so all Task 1 runs read it under the same conditions.
- **Correctness:** `gen` writes the true counts to `numbers.txt.counts`, and every run is compared
  with them (`CORRECT` / `WRONG`). Only Task 1b version 1 is expected to be `WRONG`.

## Key design points (for the defense)

- **1a boundaries:** a line belongs to the part where its first byte lies. Thread *i* processes
  `[align(start_i), align(end_i))`, where `align(p)` is the start of the first line at or after
  byte `p`. Neighbouring threads compute `align` of the same offset, so one part ends exactly where
  the next begins: no gap and no overlap. This was tested with 1–1000 lines, 1–64 threads,
  `\n` and `\r\n` endings, and a file without a final newline.
- **1b version 1** uses plain `long` fields and `counter++` (load, add, store). Two threads can load
  the same value, and one of the two increments is lost.
- **1c** closes the queue with one poison-pill batch per worker. The queue is FIFO, so the pills
  come after all the real batches.
- **2** deals file *k* to thread *k mod T*: 8 files on 1 thread, 4 each on 2 threads, 2 each on 4 threads.
