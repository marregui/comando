package dev.marregui.comando.core.result;

import java.util.concurrent.ConcurrentHashMap;

/** Results by request id; the state's runs refer to them by that id. */
public final class ResultRegistry {

    private final ConcurrentHashMap<Long, ResultView> results = new ConcurrentHashMap<>();

    public void put(ResultView result) {
        results.put(result.requestId(), result);
    }

    public ResultView get(long requestId) {
        return results.get(requestId);
    }

    public void release(long requestId) {
        results.remove(requestId);
    }

    public int size() {
        return results.size();
    }
}
