package com.nexodb.util;

import java.nio.file.*;

public final class WorkspaceRecoveryStoreTest {
    public static void main(String[] args)throws Exception{
        Path file=Files.createTempDirectory("adisdb-recovery-").resolve("recovery.properties");WorkspaceRecoveryStore first=new WorkspaceRecoveryStore(file);if(first.start().unexpectedShutdown())throw new AssertionError("Fresh workspace reported a crash");BackgroundOperations.Operation operation=BackgroundOperations.create(BackgroundOperations.Kind.EXPORT,"Exportar resultado","Producción",()->{},null);first.checkpoint("Producción","sistema",BackgroundOperations.snapshots());WorkspaceRecoveryStore second=new WorkspaceRecoveryStore(file);var recovered=second.start();if(!recovered.unexpectedShutdown()||recovered.tasks().isEmpty())throw new AssertionError("Interrupted work was not recovered");operation.cancelled("");second.cleanShutdown();if(new WorkspaceRecoveryStore(file).start().unexpectedShutdown())throw new AssertionError("Clean shutdown reported as crash");
        System.out.println("WorkspaceRecoveryStoreTest: OK (crash marker and data-free task descriptors)");
    }
}
