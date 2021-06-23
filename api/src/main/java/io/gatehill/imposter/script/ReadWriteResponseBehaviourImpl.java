package io.gatehill.imposter.script;

import java.util.HashMap;
import java.util.Map;

/**
 * @author Pete Cornish {@literal <outofcoffee@gmail.com>}
 */
public class ReadWriteResponseBehaviourImpl implements ReadWriteResponseBehaviour {
    private ResponseBehaviourType behaviourType = ResponseBehaviourType.DEFAULT_BEHAVIOUR;
    private int statusCode = 200;
    private String responseFile;
    private String responseData;
    private String exampleName;
    private final Map<String, String> responseHeaders = new HashMap<>();
    private boolean behaviourConfigured;
    private PerformanceSimulationConfig performanceSimulationConfig;

    @Override
    public Map<String, String> getResponseHeaders() {
        return responseHeaders;
    }

    @Override
    public int getStatusCode() {
        return statusCode;
    }

    @Override
    public String getResponseFile() {
        return responseFile;
    }

    @Override
    public String getResponseData() {
        return responseData;
    }

    @Override
    public String getExampleName() {
        return exampleName;
    }

    @Override
    public ResponseBehaviourType getBehaviourType() {
        return behaviourType;
    }

    @Override
    public PerformanceSimulationConfig getPerformanceSimulation() {
        return performanceSimulationConfig;
    }

    @Override
    public MutableResponseBehaviour withHeader(String header, String value) {
        if (value == null) {
            responseHeaders.remove(header);
        } else {
            responseHeaders.put(header, value);
        }
        return this;
    }

    /**
     * Set the HTTP status code for the response.
     *
     * @param statusCode the HTTP status code
     * @return this
     */
    @Override
    public MutableResponseBehaviour withStatusCode(int statusCode) {
        this.statusCode = statusCode;
        return this;
    }

    /**
     * Respond with the content of a static file.
     *
     * @param responseFile the response file
     * @return this
     */
    @Override
    public MutableResponseBehaviour withFile(String responseFile) {
        this.responseFile = responseFile;
        return this;
    }

    /**
     * Respond with empty content, or no records.
     *
     * @return this
     */
    @Override
    public MutableResponseBehaviour withEmpty() {
        this.responseFile = null;
        return this;
    }

    @Override
    public MutableResponseBehaviour withData(String responseData) {
        this.responseData = responseData;
        return this;
    }

    @Override
    public MutableResponseBehaviour withExampleName(String exampleName) {
        this.exampleName = exampleName;
        return this;
    }

    /**
     * Use the plugin's default behaviour to respond
     *
     * @return this
     */
    @Override
    public MutableResponseBehaviour usingDefaultBehaviour() {
        if (behaviourConfigured) {
            throw new IllegalStateException("Response already handled");
        } else {
            behaviourConfigured = true;
        }

        behaviourType = ResponseBehaviourType.DEFAULT_BEHAVIOUR;
        return this;
    }

    /**
     * Skip the plugin's default behaviour when responding.
     *
     * @return this
     */
    @Override
    public MutableResponseBehaviour skipDefaultBehaviour() {
        if (behaviourConfigured) {
            throw new IllegalStateException("Response already handled");
        } else {
            behaviourConfigured = true;
        }

        behaviourType = ResponseBehaviourType.SHORT_CIRCUIT;
        return this;
    }

    /**
     * @deprecated use {@link #skipDefaultBehaviour()} instead
     * @return this
     */
    @Deprecated
    @Override
    public MutableResponseBehaviour immediately() {
        return skipDefaultBehaviour();
    }

    /**
     * Syntactic sugar.
     *
     * @return this
     */
    @Override
    public MutableResponseBehaviour respond() {
        return this;
    }

    /**
     * Syntactic sugar that executes the Runnable immediately.
     *
     * @return this
     */
    @Override
    public MutableResponseBehaviour respond(Runnable closure) {
        closure.run();
        return this;
    }

    /**
     * Syntactic sugar.
     *
     * @return this
     */
    @Override
    public MutableResponseBehaviour and() {
        return this;
    }

    @Override
    public MutableResponseBehaviour withPerformance(PerformanceSimulationConfig performance) {
        performanceSimulationConfig = performance;
        return this;
    }

    @Override
    public MutableResponseBehaviour withDelay(int exactDelayMs) {
        performanceSimulationConfig = new PerformanceSimulationConfig();
        performanceSimulationConfig.setExactDelayMs(exactDelayMs);
        return this;
    }

    @Override
    public MutableResponseBehaviour withDelayRange(int minDelayMs, int maxDelayMs) {
        performanceSimulationConfig = new PerformanceSimulationConfig();
        performanceSimulationConfig.setMinDelayMs(minDelayMs);
        performanceSimulationConfig.setMaxDelayMs(maxDelayMs);
        return this;
    }
}
