# Running the application

There is a single configuration profile -- `src/main/resources/application.properties` -- that
always runs against a real local (or production) MySQL instance. It is gitignored (holds real
local credentials once filled in); copy `application.properties.example` to that filename and
fill in your own values before first run. See the main [README.md](README.md) for full setup.

Use the supplied helper scripts to run the application:

- POSIX (macOS / Linux / WSL):

```bash
./run-dev.sh
```

- Windows (cmd.exe):

```
run-dev.bat
```

What the scripts do:
- Use the Gradle wrapper `./gradlew` to run `bootRun`.

If you prefer to run Gradle directly:

```bash
./gradlew bootRun
```

If you want to launch the assembled JAR instead, build first and run:

```bash
./gradlew bootJar -x test
java -jar build/libs/*.jar
```
