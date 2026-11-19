package com.nexodb.util;

import java.nio.file.Files;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class BackgroundOperationsTest {
    public static void main(String[] args)throws Exception {
        var settings=Files.createTempDirectory("adisdb-operations").resolve("settings.properties");
        System.setProperty("nexodb.settingsFile",settings.toString());
        BackgroundOperations.clearFinished();BackgroundOperations.configure(1,30);

        BackgroundOperations.Operation first=BackgroundOperations.create(BackgroundOperations.Kind.QUERY,"Consulta","Producción",()->{},null);
        first.awaitTurn();
        BackgroundOperations.Operation second=BackgroundOperations.create(BackgroundOperations.Kind.COUNT,"Conteo","Producción",()->{},null);
        CountDownLatch secondRunning=new CountDownLatch(1);
        Thread waiter=new Thread(()->{try{second.awaitTurn();secondRunning.countDown();}catch(InterruptedException ignored){Thread.currentThread().interrupt();}});
        waiter.start();Thread.sleep(120);
        equal(BackgroundOperations.State.QUEUED,second.snapshot().state());
        equal(1,BackgroundOperations.queuedCount());
        first.complete("12 filas");
        if(!secondRunning.await(2,TimeUnit.SECONDS))throw new AssertionError("Queued operation never obtained a slot");
        equal(BackgroundOperations.State.RUNNING,second.snapshot().state());second.complete("12 filas");waiter.join(2_000);

        BackgroundOperations.configure(2,1,30);
        BackgroundOperations.Operation connectionFirst=BackgroundOperations.create(BackgroundOperations.Kind.EXPORT,"Exportación 1","Conexión A",()->{},null);connectionFirst.awaitTurn();
        BackgroundOperations.Operation sameConnection=BackgroundOperations.create(BackgroundOperations.Kind.COUNT,"Conteo A","Conexión A",()->{},null);CountDownLatch sameRunning=new CountDownLatch(1);
        Thread sameWaiter=new Thread(()->{try{sameConnection.awaitTurn();sameRunning.countDown();}catch(InterruptedException ignored){Thread.currentThread().interrupt();}});sameWaiter.start();
        BackgroundOperations.Operation otherConnection=BackgroundOperations.create(BackgroundOperations.Kind.QUERY,"Consulta B","Conexión B",()->{},null);otherConnection.awaitTurn();
        equal(BackgroundOperations.State.QUEUED,sameConnection.snapshot().state());equal(BackgroundOperations.State.RUNNING,otherConnection.snapshot().state());
        connectionFirst.complete("");if(!sameRunning.await(2,TimeUnit.SECONDS))throw new AssertionError("Per-connection slot was not released");sameConnection.complete("");otherConnection.complete("");sameWaiter.join(2_000);

        BackgroundOperations.configure(1,1,30);
        BackgroundOperations.Operation blocker=BackgroundOperations.create(BackgroundOperations.Kind.EXPORT,"Bloqueador","Conexión X",()->{},null);blocker.awaitTurn();
        AtomicBoolean speculativeCancelled=new AtomicBoolean();BackgroundOperations.Operation speculative=BackgroundOperations.create(BackgroundOperations.Kind.PREFETCH,"Precarga","Conexión A",()->speculativeCancelled.set(true),null);
        Thread speculativeWaiter=new Thread(()->{try{speculative.awaitTurn();}catch(InterruptedException ignored){Thread.currentThread().interrupt();}catch(java.util.concurrent.CancellationException ignored){}});speculativeWaiter.start();
        BackgroundOperations.Operation interactive=BackgroundOperations.create(BackgroundOperations.Kind.RELOAD,"Recarga","Conexión A",()->{},null);CountDownLatch interactiveRunning=new CountDownLatch(1);
        Thread interactiveWaiter=new Thread(()->{try{interactive.awaitTurn();interactiveRunning.countDown();}catch(InterruptedException ignored){Thread.currentThread().interrupt();}});interactiveWaiter.start();
        equal(BackgroundOperations.State.CANCELLED,speculative.snapshot().state());equal(true,speculativeCancelled.get());
        blocker.complete("");if(!interactiveRunning.await(2,TimeUnit.SECONDS))throw new AssertionError("Interactive work did not overtake speculative work");interactive.complete("");interactiveWaiter.join(2_000);speculativeWaiter.join(2_000);

        AtomicBoolean cancelled=new AtomicBoolean();
        BackgroundOperations.Operation timeout=BackgroundOperations.create(BackgroundOperations.Kind.EXPORT,"Exportación","Producción",()->cancelled.set(true),null,1);
        timeout.awaitTurn();long deadline=System.currentTimeMillis()+3_000;
        while(timeout.snapshot().state()!=BackgroundOperations.State.TIMED_OUT&&System.currentTimeMillis()<deadline)Thread.sleep(25);
        equal(BackgroundOperations.State.TIMED_OUT,timeout.snapshot().state());equal(true,cancelled.get());

        CountDownLatch retried=new CountDownLatch(1);
        BackgroundOperations.Operation retry=BackgroundOperations.create(BackgroundOperations.Kind.RELOAD,"Recarga","Producción",()->{},retried::countDown);
        retry.awaitTurn();retry.fail(new SQLException("jdbc:mysql://server/db password=secret","08006"));
        BackgroundOperations.retry(retry.id());
        if(!retried.await(2,TimeUnit.SECONDS))throw new AssertionError("Safe retry action was not invoked");
        String all=BackgroundOperations.snapshots().toString().toLowerCase();
        if(all.contains("secret")||all.contains("jdbc:mysql")||all.contains("select "))throw new AssertionError("Sensitive operation data leaked: "+all);
        if(BackgroundOperations.snapshots().stream().noneMatch(item->item.state()==BackgroundOperations.State.TIMED_OUT&&item.timedOut()))throw new AssertionError("Timeout history was not retained");
        List<BackgroundOperations.Snapshot> snapshots=BackgroundOperations.snapshots();
        if(snapshots.isEmpty()||snapshots.stream().anyMatch(BackgroundOperations.Snapshot::active))throw new AssertionError("Operations did not finish cleanly");
        BackgroundOperations.clearFinished();equal(0,BackgroundOperations.snapshots().size());
        BackgroundOperations.configure(4,2,120);
        System.out.println("BackgroundOperationsTest: OK (priority queue, global/per-connection limits, timeout, cancel, safe retry and private history)");
    }
    private static void equal(Object expected,Object actual){if(!Objects.equals(expected,actual))throw new AssertionError(expected+" != "+actual);}
}
