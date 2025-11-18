package io.questdb.desktop.model.rocksdb;

import org.jetbrains.annotations.NotNull;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksIterator;

import java.util.Arrays;
import java.util.List;

public class RocksDBExample {

    static {
        RocksDB.loadLibrary();
    }

    public static void main(final @NotNull String @NotNull [] args) throws Exception {
        try (final Options options = new Options().setCreateIfMissing(true).optimizeForSmallDb()) {
            try (final RocksDB rocksDB = RocksDB.open(options, "./rocksdb-data/")) {
                byte[] key = "Hello".getBytes();
                rocksDB.put(key, "World".getBytes());
                System.out.println(new String(rocksDB.get(key)));
                rocksDB.put("SecondKey".getBytes(), "SecondValue".getBytes());
                List<byte[]> keys = Arrays.asList(key, "SecondKey".getBytes(), "missKey".getBytes());
                List<byte[]> values = rocksDB.multiGetAsList(keys);
                for (int i = 0; i < keys.size(); i++) {
                    System.out.println("multiGet " + new String(keys.get(i)) + ":" + (values.get(i) != null ? new String(values.get(i)) : null));
                }
                RocksIterator iter = rocksDB.newIterator();
                for (iter.seekToFirst(); iter.isValid(); iter.next()) {
                    System.out.println("iterator key:" + new String(iter.key()) + ", iter value:" + new String(iter.value()));
                }
                rocksDB.delete(key);
                System.out.println("after remove key:" + new String(key));
                iter = rocksDB.newIterator();
                for (iter.seekToFirst(); iter.isValid(); iter.next()) {
                    System.out.println("iterator key:" + new String(iter.key()) + ", iter value:" + new String(iter.value()));
                }
            }
        }
    }
}