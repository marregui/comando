package dev.marregui.comando.frontear.link;

import java.util.List;
import java.util.Set;

/** The links of a broker to its peers, changed while they run. */
public interface Mesh {

    /** Adds the link {@code spec} names, or replaces the one with its id; refuses a value out of range. */
    void put(LinkSpec spec);

    /** Stops and forgets the link {@code id}; nothing when there is none. */
    void remove(String id);

    /** Replaces the whole set of filters of link {@code id} in {@code direction}; refuses an unknown link. */
    void filters(String id, Direction direction, Set<String> filters);

    /** Every link's state, by id. */
    List<LinkState> state();
}
