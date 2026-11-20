package hd.engine.mat;

import hd.engine.api.HeapEngine;
import hd.engine.tck.EngineContract;

import java.util.ServiceLoader;

class MatEngineContractTest extends EngineContract {

    @Override
    protected HeapEngine engine() {
        // discovered the same way the daemon does it
        return ServiceLoader.load(HeapEngine.class).stream()
                .map(ServiceLoader.Provider::get)
                .filter(e -> e.id().equals("mat"))
                .findFirst().orElseThrow();
    }
}
