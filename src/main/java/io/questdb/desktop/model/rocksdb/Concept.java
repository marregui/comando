package io.questdb.desktop.model.rocksdb;

import java.util.*;
import java.util.stream.Collectors;

public class Concept {

    public static void main(final String[] args) {
        final List<Client> store0 = List.of(
                new Client("client-0", -1),
                new Client("client-4", 1110),
                new Client("client-8", -11),
                new Client("client-12", 14),
                new Client("client-16", 998));
        final List<Client> store1 = List.of(
                new Client("client-1", 20),
                new Client("client-2", 30),
                new Client("client-5", -100),
                new Client("client-9", 5),
                new Client("client-11", 1000),
                new Client("client-15", 99));
        final List<Client> store2 = List.of(
                new Client("client-3", -10),
                new Client("client-6", 997),
                new Client("client-7", -20),
                new Client("client-13", -10),
                new Client("client-14", 3215));

        final Comparator<Client> userOrderingCriteria = Comparator.comparing(Client::score);

        // fetch top 2 records
        // Node A
        List<Client> r1 = calc(store0, 15, userOrderingCriteria);
        System.out.println(r1);
        // Node B
        List<Client> r2 = calc(store1, 15, userOrderingCriteria);
        System.out.println(r2);
        // Node C
        List<Client> r3 = calc(store2, 15, userOrderingCriteria);
        System.out.println(r3);

        // on the coordinator
        PriorityQueue<LinkedList<Client>> pq = new PriorityQueue<>((o1, o2) -> userOrderingCriteria.compare(o2.peek(), o1.peek()));
        pq.add(r1.stream().sorted(userOrderingCriteria.reversed()).collect(Collectors.toCollection(LinkedList::new)));
        pq.add(r2.stream().sorted(userOrderingCriteria.reversed()).collect(Collectors.toCollection(LinkedList::new)));
        pq.add(r3.stream().sorted(userOrderingCriteria.reversed()).collect(Collectors.toCollection(LinkedList::new)));

        // return top 5 as the final result
        final int max = 5;
        int c = 0;
        final List<Client> finalRes = new ArrayList<>();
        while (c < max && !pq.isEmpty()) {
            final LinkedList<Client> list = pq.poll();
            finalRes.add(list.poll());
            if (!list.isEmpty()) {
                pq.add(list);
            }
            c++;
        }
        System.out.println();
        System.out.println(finalRes);
    }

    private static List<Client> calc(final List<Client> data, final int maxSize, final Comparator<Client> comparator) {
        final PriorityQueue<Client> pq = new PriorityQueue<>(comparator);
        for (final Client client : data) {
            if (pq.size() < maxSize) {
                pq.add(client);
            } else if (comparator.compare(client, pq.peek()) > 0) {
                pq.poll();
                pq.add(client);
            }
        }

        return pq.stream().toList();
    }

    private record Client(String name, int score) {

        @Override
        public String toString() {
            return name + " (" + score + ')';
        }
    }
}