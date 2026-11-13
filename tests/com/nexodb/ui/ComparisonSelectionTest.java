package com.nexodb.ui;

import com.nexodb.MetadataFixture;
import com.nexodb.db.JdbcSession;
import com.nexodb.model.DatabaseType;
import javax.swing.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import static com.nexodb.MetadataFixture.equal;

public final class ComparisonSelectionTest {
    public static void main(String[] args)throws Exception {
        CountDownLatch release=new CountDownLatch(1),entered=new CountDownLatch(1);
        AtomicInteger readsA=new AtomicInteger(),readsB=new AtomicInteger(),errors=new AtomicInteger();
        JdbcSession a=MetadataFixture.session("A",DatabaseType.MYSQL,(p,m,v)->{
            if(!m.getName().equals("getCatalogs"))throw new AssertionError(m.getName());
            readsA.incrementAndGet();entered.countDown();release.await(5,TimeUnit.SECONDS);
            return MetadataFixture.rows(new String[]{"TABLE_CAT"},new Object[]{"alpha"},new Object[]{"common"});
        });
        JdbcSession b=MetadataFixture.session("B",DatabaseType.MYSQL,(p,m,v)->{
            if(!m.getName().equals("getCatalogs"))throw new AssertionError(m.getName());readsB.incrementAndGet();
            return MetadataFixture.rows(new String[]{"TABLE_CAT"},new Object[]{"bee"},new Object[]{"common"});
        });
        ComparisonChoices memory=new ComparisonChoices(); memory.tables(Set.of("table1"),true,false);
        JComboBox<String> source=new JComboBox<>(),target=new JComboBox<>();
        ComparisonDatabaseSelector loader=new ComparisonDatabaseSelector(memory,()->{},error->errors.incrementAndGet());
        var left=loader.side(source,true);var right=loader.side(target,false);
        SwingUtilities.invokeAndWait(()->left.load(a));if(!entered.await(5,TimeUnit.SECONDS))throw new AssertionError("Read did not start");
        SwingUtilities.invokeAndWait(()->{left.load(b);right.load(b);});await(()->left.ready()&&right.ready());
        equal(1,readsB.get());SwingUtilities.invokeAndWait(()->source.setSelectedItem("common"));
        release.countDown();Thread.sleep(100);SwingUtilities.invokeAndWait(()->equal("common",source.getSelectedItem()));
        SwingUtilities.invokeAndWait(()->left.load(a));await(left::ready);
        SwingUtilities.invokeAndWait(()->{equal("common",source.getSelectedItem());source.setSelectedItem("alpha");left.load(b);});await(left::ready);
        SwingUtilities.invokeAndWait(()->{equal("common",source.getSelectedItem());equal("bee",target.getSelectedItem());left.load(a);});await(left::ready);
        SwingUtilities.invokeAndWait(()->equal("alpha",source.getSelectedItem()));
        equal(1,readsA.get());equal(1,readsB.get());equal(Set.of("table1"),memory.tables());equal(true,memory.tablesConfigured());equal(0,errors.get());
        SwingUtilities.invokeAndWait(loader::close);
        equal(a,memory.preferred(true,List.of(a,b)));equal(b,memory.preferred(false,List.of(a,b)));
        ComparisonDatabaseSelector reopened=new ComparisonDatabaseSelector(memory,()->{},error->errors.incrementAndGet());var restored=reopened.side(source,true);
        SwingUtilities.invokeAndWait(()->restored.load(a));await(restored::ready);SwingUtilities.invokeAndWait(()->equal("alpha",source.getSelectedItem()));
        equal("first",ComparisonDatabaseSelector.choose(List.of("first","second"),"not-a-real-database"));
        SwingUtilities.invokeAndWait(reopened::close);
        System.out.println("ComparisonSelectionTest: OK (stale requests, per-connection choices, catalog cache and reopen)");
    }
    private static void await(BooleanSupplier condition)throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);AtomicBoolean ready=new AtomicBoolean();
        while(System.nanoTime()<deadline){SwingUtilities.invokeAndWait(()->ready.set(condition.getAsBoolean()));if(ready.get())return;Thread.sleep(10);}
        throw new AssertionError("Timed out waiting for selection");
    }
}
