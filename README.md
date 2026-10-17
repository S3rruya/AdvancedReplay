# AdvancedReplay

> SpaceCraft fork: the Java 21 / Minecraft 1.21.11 Folia recorder and isolated Paper arena player are in [`folia-replay/`](folia-replay/README.md). Build with `./gradlew :folia-replay:build`. The original plugin below is retained separately and is not Folia-compatible.

AdvancedReplay is a Minecraft 1.8 & 1.21 Replay plugin. It can record players on your Server and save the recorded data to a file or database, so you can watch the replays at any time. Currently it records almost every action a player does and can be easily controlled with commands or the API.

## Downloads
**Modrinth**: https://modrinth.com/plugin/advancedreplay  
**Spigot**: https://www.spigotmc.org/resources/advancedreplay-1-8-1-21.52849/

## API
### Maven
#### Add the repositories:
```xml


<repository>
    <id>jitpack.io</id>
    <url>https://jitpack.io</url>
</repository>

<repository>
    <id>maven-snapshots</id>
    <url>https://s01.oss.sonatype.org/content/repositories/snapshots/</url>
</repository>
```
#### Add the dependency:
```xml
<dependency>
    <groupId>com.github.Jumper251</groupId>
    <artifactId>AdvancedReplay</artifactId>
    <version>VERSION</version>
    <scope>provided</scope>
</dependency>
```
### Gradle
```text
repositories {
    maven { url 'https://jitpack.io' }
}

dependencies {
    compileOnly 'com.github.Jumper251:AdvancedReplay:VERSION'
}
```

### API usage
Some examples on how to use the API can be found on the plugins Spigot page.
