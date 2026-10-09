#!/bin/bash
# M1.4-R parity campaign runner
JAVA="C:/Program Files/Eclipse Adoptium/jdk-8.0.504.1-hotspot/bin/java.exe"
CP="tools/bin;third_party_reference/minecraft/minecraft_server.1.12.2.srg.jar;third_party_reference/forge/server/forge-1.12.2-14.23.5.2860.jar;third_party_reference/forge/server/libraries/io/netty/netty-all/4.1.9.Final/netty-all-4.1.9.Final.jar;third_party_reference/forge/server/libraries/com/google/guava/guava/21.0/guava-21.0.jar;third_party_reference/forge/server/libraries/org/ow2/asm/asm-debug-all/5.2/asm-debug-all-5.2.jar"
exec "$JAVA" -Djava.library.path="target/release" -cp "$CP" com.rustcraft.oracle.M14RParityHarness "$@"
