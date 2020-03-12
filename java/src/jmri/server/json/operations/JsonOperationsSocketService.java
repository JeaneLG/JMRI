package jmri.server.json.operations;

import static jmri.server.json.operations.JsonOperations.CAR;
import static jmri.server.json.operations.JsonOperations.CARS;
import static jmri.server.json.operations.JsonOperations.ENGINE;
import static jmri.server.json.operations.JsonOperations.ENGINES;
import static jmri.server.json.operations.JsonOperations.LOCATION;
import static jmri.server.json.operations.JsonOperations.LOCATIONS;
import static jmri.server.json.operations.JsonOperations.LOCATION_COMMENT;
import static jmri.server.json.operations.JsonOperations.LOCATION_NAME;
import static jmri.server.json.operations.JsonOperations.TRAIN;
import static jmri.server.json.operations.JsonOperations.TRAINS;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.IOException;
import java.util.HashMap;
import java.util.Objects;

import javax.annotation.Nonnull;

import com.fasterxml.jackson.databind.JsonNode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jmri.InstanceManager;
import jmri.JmriException;
import jmri.jmrit.operations.Identified;
import jmri.jmrit.operations.locations.Location;
import jmri.jmrit.operations.locations.LocationManager;
import jmri.jmrit.operations.rollingstock.RollingStock;
import jmri.jmrit.operations.rollingstock.cars.Car;
import jmri.jmrit.operations.rollingstock.cars.CarManager;
import jmri.jmrit.operations.rollingstock.engines.Engine;
import jmri.jmrit.operations.rollingstock.engines.EngineManager;
import jmri.jmrit.operations.trains.Train;
import jmri.jmrit.operations.trains.TrainManager;
import jmri.server.json.JSON;
import jmri.server.json.JsonConnection;
import jmri.server.json.JsonException;
import jmri.server.json.JsonRequest;
import jmri.server.json.JsonSocketService;

/**
 * @author Randall Wood (C) 2016, 2019, 2020
 */
public class JsonOperationsSocketService extends JsonSocketService<JsonOperationsHttpService> {

    private final HashMap<String, SingleListener<Car>> carListeners = new HashMap<>();
    private final HashMap<String, SingleListener<Engine>> engineListeners = new HashMap<>();
    private final HashMap<String, SingleListener<Location>> locationListeners = new HashMap<>();
    private final HashMap<String, SingleListener<Train>> trainListeners = new HashMap<>();
    private final CarsListener carsListener = new CarsListener();
    private final EnginesListener enginesListener = new EnginesListener();
    private final LocationsListener locationsListener = new LocationsListener();
    private final TrainsListener trainsListener = new TrainsListener();

    private static final Logger log = LoggerFactory.getLogger(JsonOperationsSocketService.class);

    public JsonOperationsSocketService(JsonConnection connection) {
        this(connection, new JsonOperationsHttpService(connection.getObjectMapper()));
    }

    protected JsonOperationsSocketService(JsonConnection connection, JsonOperationsHttpService service) {
        super(connection, service);
    }

    @Override
    public void onMessage(String type, JsonNode data, JsonRequest request)
            throws IOException, JmriException, JsonException {
        String name = data.path(JSON.NAME).asText();
        switch (request.method) {
            case JSON.GET:
                connection.sendMessage(service.doGet(type, name, data, request), request.id);
                break;
            case JSON.DELETE:
                service.doDelete(type, name, data, request);
                break;
            case JSON.PUT:
                connection.sendMessage(service.doPut(type, name, data, request), request.id);
                break;
            case JSON.POST:
            default:
                connection.sendMessage(service.doPost(type, name, data, request), request.id);
        }
        // add listener to name if not already listening
        if (!request.method.equals(JSON.DELETE)) {
            if (name.isEmpty()) {
                name = RollingStock.createId(data.path(JSON.ROAD).asText(), data.path(JSON.NUMBER).asText());
            }
            switch (type) {
                case CAR:
                    carListeners.computeIfAbsent(name, id -> {
                        CarListener l = new CarListener(id);
                        InstanceManager.getDefault(CarManager.class).getById(id).addPropertyChangeListener(l);
                        return l;
                    });
                    break;
                case ENGINE:
                    engineListeners.computeIfAbsent(name, id -> {
                        EngineListener l = new EngineListener(id);
                        InstanceManager.getDefault(EngineManager.class).getById(id).addPropertyChangeListener(l);
                        return l;
                    });
                    break;
                case LOCATION:
                    locationListeners.computeIfAbsent(name, id -> {
                        LocationListener l = new LocationListener(id);
                        InstanceManager.getDefault(LocationManager.class).getLocationById(id).addPropertyChangeListener(l);
                        return l;
                    });
                    break;
                case TRAIN:
                    trainListeners.computeIfAbsent(name, id -> {
                        TrainListener l = new TrainListener(id);
                        InstanceManager.getDefault(TrainManager.class).getTrainById(id).addPropertyChangeListener(l);
                        return l;
                    });
                    break;
                default:
                    // other types ignored
                    break;
            }
        } else {
            switch (type) {
                case CAR:
                    carListeners.remove(name);
                    break;
                case ENGINE:
                    engineListeners.remove(name);
                    break;
                case LOCATION:
                    locationListeners.remove(name);
                    break;
                case TRAIN:
                    trainListeners.remove(name);
                    break;
                default:
                    // other types ignored
                    break;
            }
        }
    }

    @Override
    public void onList(String type, JsonNode data, JsonRequest request)
            throws IOException, JmriException, JsonException {
        connection.sendMessage(service.doGetList(type, data, request), request.id);
        switch (type) {
            case CAR:
            case CARS:
                log.debug("adding CarsListener");
                InstanceManager.getDefault(CarManager.class).addPropertyChangeListener(carsListener);
                break;
            case ENGINE:
            case ENGINES:
                log.debug("adding EnginesListener");
                InstanceManager.getDefault(EngineManager.class).addPropertyChangeListener(enginesListener);
                break;
            case LOCATION:
            case LOCATIONS:
                log.debug("adding LocationsListener");
                InstanceManager.getDefault(LocationManager.class).addPropertyChangeListener(locationsListener);
                break;
            case TRAIN:
            case TRAINS:
                log.debug("adding TrainsListener");
                InstanceManager.getDefault(TrainManager.class).addPropertyChangeListener(trainsListener);
                break;
            default:
                break;
        }
    }

    private void sendListChange(String type) throws IOException {
        try {
            connection.sendMessage(service.doGetList(type, service.getObjectMapper().createObjectNode(),
                    new JsonRequest(getLocale(), getVersion(), JSON.GET, 0)), 0);
        } catch (JsonException ex) {
            log.warn("json error sending Engines: {}", ex.getJsonMessage());
            connection.sendMessage(ex.getJsonMessage(), 0);
        }
    }

    @Override
    public void onClose() {
        carListeners.values().forEach(listener -> listener.object.removePropertyChangeListener(listener));
        carListeners.clear();
        engineListeners.values().forEach(listener -> listener.object.removePropertyChangeListener(listener));
        engineListeners.clear();
        locationListeners.values().forEach(listener -> listener.object.removePropertyChangeListener(listener));
        locationListeners.clear();
        trainListeners.values().forEach(listener -> listener.object.removePropertyChangeListener(listener));
        trainListeners.clear();
        InstanceManager.getDefault(CarManager.class).removePropertyChangeListener(carsListener);
        InstanceManager.getDefault(EngineManager.class).removePropertyChangeListener(enginesListener);
        InstanceManager.getDefault(LocationManager.class).removePropertyChangeListener(locationsListener);
        InstanceManager.getDefault(TrainManager.class).removePropertyChangeListener(trainsListener);
    }

    private abstract class SingleListener<O extends Identified> implements PropertyChangeListener {
        
        protected final O object;
        
        protected SingleListener(@Nonnull O obj) {
            Objects.requireNonNull(obj);
            this.object = obj;
        }
        
        protected void propertyChange(String type, HashMap<String, SingleListener<O>> map) {
            try {
                sendSingleChange(type, object);
            } catch (IOException ex) {
                // stop listening to this object on error
                object.removePropertyChangeListener(this);
                map.remove(object.getId());
            }
        }

        private <E extends Identified> void sendSingleChange(String type, E object) throws IOException {
            try {
                connection.sendMessage(service.doGet(type, object.getId(),
                        connection.getObjectMapper().createObjectNode(),
                        new JsonRequest(getLocale(), getVersion(), JSON.GET, 0)), 0);
            } catch (JsonException ex) {
                log.warn("json error sending {}: {}", type, ex.getJsonMessage());
                connection.sendMessage(ex.getJsonMessage(), 0);
            }
        }
    }

    private class CarListener extends SingleListener<Car> {

        protected CarListener(String id) {
            super(InstanceManager.getDefault(CarManager.class).getById(id));
        }

        @Override
        public void propertyChange(PropertyChangeEvent evt) {
            propertyChange(CAR, carListeners);
        }
    }

    private class CarsListener implements PropertyChangeListener {

        @Override
        public void propertyChange(PropertyChangeEvent evt) {
            log.debug("in CarsListener for '{}' ('{}' => '{}')", evt.getPropertyName(), evt.getOldValue(),
                    evt.getNewValue());
            try {
                sendListChange(CARS);
            } catch (IOException ex) {
                // if we get an error, de-register
                log.debug("deregistering carsListener due to IOException");
                InstanceManager.getDefault(CarManager.class).removePropertyChangeListener(carsListener);
            }
        }
    }

    private class EngineListener extends SingleListener<Engine> {

        protected EngineListener(String id) {
            super(InstanceManager.getDefault(EngineManager.class).getById(id));
        }

        @Override
        public void propertyChange(PropertyChangeEvent evt) {
            propertyChange(ENGINE, engineListeners);
        }
    }

    private class EnginesListener implements PropertyChangeListener {

        @Override
        public void propertyChange(PropertyChangeEvent evt) {
            log.debug("in EnginesListener for '{}' ('{}' => '{}')", evt.getPropertyName(), evt.getOldValue(),
                    evt.getNewValue());
            try {
                sendListChange(ENGINE);
            } catch (IOException ex) {
                // if we get an error, de-register
                log.debug("deregistering enginesListener due to IOException");
                InstanceManager.getDefault(CarManager.class).removePropertyChangeListener(enginesListener);
            }
        }
    }

    private class LocationListener extends SingleListener<Location> {

        protected LocationListener(String id) {
            super(InstanceManager.getDefault(LocationManager.class).getLocationById(id));
        }

        @Override
        public void propertyChange(PropertyChangeEvent evt) {
            // only send changes to properties that are included in object
            if (evt.getPropertyName().equals(JSON.ID) ||
                    evt.getPropertyName().equals(LOCATION_NAME) ||
                    evt.getPropertyName().equals(JSON.LENGTH) ||
                    evt.getPropertyName().equals(LOCATION_COMMENT)) {
                propertyChange(LOCATION, locationListeners);
            }
        }
    }

    private class LocationsListener implements PropertyChangeListener {

        @Override
        public void propertyChange(PropertyChangeEvent evt) {
            log.debug("in LocationsListener for '{}' ('{}' => '{}')", evt.getPropertyName(), evt.getOldValue(),
                    evt.getNewValue());
            try {
                sendListChange(LOCATION);
            } catch (IOException ex) {
                // if we get an error, de-register
                log.debug("deregistering locationsListener due to IOException");
                InstanceManager.getDefault(LocationManager.class).removePropertyChangeListener(locationsListener);
            }
        }
    }

    private class TrainListener extends SingleListener<Train> {

        protected TrainListener(String id) {
            super(InstanceManager.getDefault(TrainManager.class).getTrainById(id));
        }

        @Override
        public void propertyChange(PropertyChangeEvent evt) {
            propertyChange(TRAIN, trainListeners);
        }
    }

    private class TrainsListener implements PropertyChangeListener {

        @Override
        public void propertyChange(PropertyChangeEvent evt) {
            log.debug("in TrainsListener for '{}' ('{}' => '{}')", evt.getPropertyName(), evt.getOldValue(),
                    evt.getNewValue());
            try {
                sendListChange(TRAIN);
            } catch (IOException ex) {
                // if we get an error, de-register
                log.debug("deregistering trainsListener due to IOException");
                InstanceManager.getDefault(TrainManager.class).removePropertyChangeListener(trainsListener);
            }
        }
    }
}
