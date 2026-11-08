# ⏱️ JVM Timer

**`/jvmtimer`**

Measures how long a piece of a datapack takes, from the JVM side.

A timer is a named pair of `start`/`end` calls placed around the code to measure.
Every pass through the pair is recorded, so what you read back is not a single measure but the total, the number of passes, the average, and the extremes.

```{note}
Timers are process wide and live as long as the game does: they are shared by everyone and survive a datapack reload.
Use `reset` before a measurement campaign rather than trusting a fresh id.
```

---

## 🔧 Subcommands

You can find below all the subcommands available.

---

### Start

```{describe} /jvmtimer start <id>

Start the timer, creating it if it does not exist yet.

:Arguments:
  **`id`**: Name of the timer.

:Note:
  Starting a timer twice without ending it in between is a mistake that would corrupt the measure, so the timer disables itself instead and says so in chat. Use `reset` to re-enable it.
```

---

### End

```{describe} /jvmtimer end <id>

Stop the timer and record the elapsed time as one more pass.

:Arguments:
  **`id`**: Name of the timer.

:Note:
  Starting a timer that was already running disables it and tells you in chat.
  Ending a timer that was never started disables it and tells you in chat.
  Use `reset` to re-enable it.
```

*Example: measure a function, one pass per call:*

```mcfunction
#!jvmtimer start my_function
function test:expensive
#!jvmtimer end my_function
```

---

### Profile file

```{describe} #!jvmtimer profile file

A directive placed anywhere in a `.mcfunction` file. Every call to that function is automatically timed without needing manual `start`/`end` pairs.

The timer id is the function's resource location (e.g. `my_pack:dir/my_function`).

:Note:
  The directive does not inject commands into the function, so line numbers and the debugger are not affected, and the injection is does at parsing time, to introduce minimal overhead.
  The timing includes sub-functions called.
  Unlike manual `start`/`end` pairs, this works correctly with functions that use `return`, an `end` placed after a `return` would never execute, making accurate timing impossible.
  Recursive function is NOT supported.
```

*Example: profile an entire function:*

```mcfunction
#!jvmtimer profile file
say starting expensive work
function test:sub_task
say done
```

Then use `/jvmtimer get my_pack:dir/my_function` to see the results.

---

### Profile start (directive)

```{describe} #!jvmtimer profile start <namespace>

A directive placed in a `.mcfunction` file. When the function is called, every loaded function in the given namespace is registered for automatic profiling, as if `/jvmtimer profile start <namespace>` had been run.

:Arguments:
  **`namespace`**: The namespace to profile.

:Note:
  This is useful to enable profiling from within a datapack, for example in a `load.mcfunction`, without needing to type the command manually.
  The `*` wildcard is also supported.
```

*Example: an init function that enables profiling for its own namespace:*

```mcfunction
#!jvmtimer profile start my_pack
say Profiling enabled for my_pack
```

---

### Get

```{describe} /jvmtimer get <id>

Print what the timer recorded: total time, number of passes, average time, and the longest and shortest pass.

:Arguments:
  **`id`**: Name of the timer.
```

*Prints:*

```
Timer id: my_function
Total time: 42.5ms
Count: 100
Average time: 0.425ms
Min/Max time: 112.0μs/981.0μs
```

---

### Reset

```{describe} /jvmtimer reset <id>

Throw away everything the timer recorded, and enable it again if it had disabled itself.

:Arguments:
  **`id`**: Name of the timer.
```

---

### Disable

```{describe} /jvmtimer disable <id>

Clear the timer and make it ignore its `start` and `end` calls.

Use it to silence a timer whose directives are still in the datapack, without editing the functions. `reset` turns it back on.

:Arguments:
  **`id`**: Name of the timer.
```

---

## Profile namespace

Bulk profiling: register or unregister every function in a namespace (or all namespaces) at once, without adding `#!jvmtimer profile file` to each file.

---

### Profile start

```{describe} /jvmtimer profile start <namespace|*>

Register every loaded function in the given namespace for automatic profiling. Use `*` to register all namespaces.

Functions already registered (e.g. via the directive) are not duplicated.

:Arguments:
  **`namespace`**: The namespace to profile, or `*` for all.

:Note:
  If the namespace does not match any loaded function, an error is shown in chat.
  Autocomplete suggests the available namespaces and `*`.
```

---

### Profile end

```{describe} /jvmtimer profile end <namespace|*>

Unregister every function in the given namespace from automatic profiling. Use `*` to unregister all namespaces.

This also overrides functions registered via the `#!jvmtimer profile file` directive.
Accumulated timer data is preserved, use `profile reset` or `reset` to clear it.

:Arguments:
  **`namespace`**: The namespace to stop profiling, or `*` for all.
```

---

### Profile reset

```{describe} /jvmtimer profile reset <namespace|*>

Reset the timer of every function in the given namespace (clear total, count, min, max), but keep the functions registered for profiling.

:Arguments:
  **`namespace`**: The namespace whose timers to reset, or `*` for all.
```
