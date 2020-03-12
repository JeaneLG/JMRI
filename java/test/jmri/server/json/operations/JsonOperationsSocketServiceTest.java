package jmri.server.json.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;

import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Locale;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import jmri.InstanceManager;
import jmri.JmriException;
import jmri.jmrit.operations.rollingstock.cars.CarManager;
import jmri.jmrit.operations.rollingstock.cars.Kernel;
import jmri.server.json.JSON;
import jmri.server.json.JsonException;
import jmri.server.json.JsonMockConnection;
import jmri.server.json.JsonRequest;
import jmri.util.JUnitOperationsUtil;
import jmri.util.JUnitUtil;

public class JsonOperationsSocketServiceTest {

    private JsonMockConnection connection;
    private JsonOperationsSocketService service;
    private ObjectMapper mapper;
    private final Locale locale = Locale.ENGLISH;

    @Test
    public void testOnListCar() throws IOException, JmriException, JsonException {
        service.onList(JsonOperations.CAR, mapper.createObjectNode(), new JsonRequest(locale, JSON.V5, JSON.GET, 0));
        JsonNode message = connection.getMessage();
        assertThat(message).isNotNull();
        assertEquals(9, message.size());
        assertEquals(JsonOperations.CAR, message.path(0).path(JSON.TYPE).asText());
        assertEquals("CP777", message.path(0).path(JSON.DATA).path(JSON.NAME).asText());
        assertEquals(JsonOperations.CAR, message.path(1).path(JSON.TYPE).asText());
        assertEquals("CP888", message.path(1).path(JSON.DATA).path(JSON.NAME).asText());
        assertEquals(JsonOperations.CAR, message.path(2).path(JSON.TYPE).asText());
        assertEquals("CP99", message.path(2).path(JSON.DATA).path(JSON.NAME).asText());
        assertEquals(JsonOperations.CAR, message.path(3).path(JSON.TYPE).asText());
        assertEquals("CPC10099", message.path(3).path(JSON.DATA).path(JSON.NAME).asText());
        assertEquals(JsonOperations.CAR, message.path(4).path(JSON.TYPE).asText());
        assertEquals("CPC20099", message.path(4).path(JSON.DATA).path(JSON.NAME).asText());
        assertEquals(JsonOperations.CAR, message.path(5).path(JSON.TYPE).asText());
        assertEquals("CPX10001", message.path(5).path(JSON.DATA).path(JSON.NAME).asText());
        assertEquals(JsonOperations.CAR, message.path(6).path(JSON.TYPE).asText());
        assertEquals("CPX10002", message.path(6).path(JSON.DATA).path(JSON.NAME).asText());
        assertEquals(JsonOperations.CAR, message.path(7).path(JSON.TYPE).asText());
        assertEquals("CPX20001", message.path(7).path(JSON.DATA).path(JSON.NAME).asText());
        assertEquals(JsonOperations.CAR, message.path(8).path(JSON.TYPE).asText());
        assertEquals("CPX20002", message.path(8).path(JSON.DATA).path(JSON.NAME).asText());
        // add car and assert new messages sent with 10 cars
        CarManager manager = InstanceManager.getDefault(CarManager.class);
        manager.newRS("GNWR", "300005");
        message = connection.getMessage();
        assertThat(message).isNotNull();
        assertThat(message.size()).isEqualTo(10);
    }

    @Test
    public void testEditCar() throws IOException, JmriException, JsonException {
        CarManager manager = InstanceManager.getDefault(CarManager.class);
        assertThat(manager.getById("GNWR300005")).isNull();
        // creates car
        service.onMessage(JsonOperations.CAR, mapper.createObjectNode().put(JSON.ROAD, "GNWR").put(JSON.NUMBER, "300005"), new JsonRequest(locale, JSON.V5, JSON.PUT, 42));
        assertThat(manager.getById("GNWR300005")).isNotNull();
        JsonNode message = connection.getMessage();
        assertThat(message).isNotNull();
        assertThat(message.path(JSON.TYPE).asText()).isEqualTo(JsonOperations.CAR);
        assertThat(message.path(JSON.DATA).path(JsonOperations.CAR_TYPE).asText()).isEqualTo("");
        // makes change
        service.onMessage(JsonOperations.CAR, mapper.createObjectNode().put(JSON.NAME, "GNWR300005").put(JsonOperations.CAR_TYPE, "Boxcar"), new JsonRequest(locale, JSON.V5, JSON.POST, 42));
        message = connection.getMessage();
        assertThat(message).isNotNull();
        assertThat(message.path(JSON.DATA).path(JsonOperations.CAR_TYPE).asText()).isEqualTo("Boxcar");
        // gets external change
        manager.getById("GNWR300005").setTypeName("Flatcar");
        message = connection.getMessage();
        assertThat(message).isNotNull();
        assertThat(message.path(JSON.DATA).path(JsonOperations.CAR_TYPE).asText()).isEqualTo("Flatcar");
        // deletes car
        service.onMessage(JsonOperations.CAR, mapper.createObjectNode().put(JSON.NAME, "GNWR300005"), new JsonRequest(locale, JSON.V5, JSON.DELETE, 42));
        assertThat(manager.getById("GNWR300005")).isNull();
    }

    @Test
    public void testOnMessageKernel() throws IOException, JmriException, JsonException {
        try {
            service.onMessage(JsonOperations.KERNEL, mapper.createObjectNode().put(JSON.NAME, "non-existant"),
                    new JsonRequest(locale, JSON.V5, JSON.GET, 42));
            fail("Expected exception not thrown");
        } catch (JsonException ex) {
            assertEquals(404, ex.getCode());
        }
    }

    @Test
    public void testOnListKernel() throws IOException, JmriException, JsonException {
        service.onList(JsonOperations.KERNEL, mapper.createObjectNode(), new JsonRequest(locale, JSON.V5, JSON.GET, 0));
        JsonNode message = connection.getMessage();
        assertThat(message).isNotNull();
        assertEquals(1, message.size());
        assertEquals(JsonOperations.KERNEL, message.path(0).path(JSON.TYPE).asText());
        JsonNode kernel = message.path(0).path(JSON.DATA);
        assertThat(kernel).isNotNull();
        assertEquals(1, kernel.path(JsonOperations.CARS).size());
        assertFalse(kernel.path(JsonOperations.LEAD).isMissingNode());
        assertEquals(kernel.path(JsonOperations.LEAD), kernel.path(JsonOperations.CARS).path(0));
        assertFalse(kernel.path(JsonOperations.WEIGHT).isMissingNode());
        assertEquals(0, kernel.path(JsonOperations.WEIGHT).asInt());
        assertEquals(94, kernel.path(JSON.LENGTH).asInt());
    }

    @Before
    public void setUp() {
        JUnitUtil.setUp();
        JUnitUtil.initIdTagManager();
        JUnitOperationsUtil.setupOperationsTests();
        JUnitOperationsUtil.initOperationsData();
        Kernel kernel = InstanceManager.getDefault(CarManager.class).newKernel("test1");
        InstanceManager.getDefault(CarManager.class).getById("CP99").setKernel(kernel);
        mapper = new ObjectMapper();
        connection = new JsonMockConnection((DataOutputStream) null);
        service = new JsonOperationsSocketService(connection, new JsonOperationsHttpService(mapper));
    }

    @SuppressWarnings("deprecation")
    @After
    public void tearDown() {
        service.onClose();
        service = null;
        connection = null;
        mapper = null;
        JUnitUtil.clearShutDownManager();
        JUnitUtil.tearDown();
    }
}
