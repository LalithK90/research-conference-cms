# Running the application (dev profile)


Use the supplied helper scripts to run the application (the `dev` profile is already the default).

- POSIX (macOS / Linux / WSL):

```bash
./run-dev.sh
```

- Windows (cmd.exe):

```
run-dev.bat
```

What the scripts do:
- Use the Gradle wrapper `./gradlew` to run `bootRun`. The application default profile is set to `dev` in `application.properties`.

If you prefer to run Gradle directly:

```bash
./gradlew bootRun
```

Notes:
- The `dev` profile should be configured in `src/main/resources/application-dev.properties`.
- If you want to launch the assembled JAR instead, build first and run:

```bash
./gradlew bootJar -x test
java -jar build/libs/*.jar --spring.profiles.active=dev
```
