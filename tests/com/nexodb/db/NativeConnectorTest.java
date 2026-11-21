package com.nexodb.db;
import java.nio.charset.StandardCharsets;
public final class NativeConnectorTest {public static void main(String[] args)throws Exception{String command=new String(RedisNativeClient.command("SET","clave","áé"),StandardCharsets.UTF_8);String expected="*3\r\n$3\r\nSET\r\n$5\r\nclave\r\n$4\r\náé\r\n";if(!expected.equals(command))throw new AssertionError(command.replace("\r","\\r").replace("\n","\\n"));System.out.println("NativeConnectorTest: OK (binary-safe Redis RESP command)");}}
