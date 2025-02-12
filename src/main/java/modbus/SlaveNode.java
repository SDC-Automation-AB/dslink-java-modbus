package modbus;

import com.serotonin.modbus4j.BatchRead;
import com.serotonin.modbus4j.BatchResults;
import com.serotonin.modbus4j.ExceptionResult;
import com.serotonin.modbus4j.ModbusMaster;
import com.serotonin.modbus4j.exception.ErrorResponseException;
import com.serotonin.modbus4j.exception.ModbusTransportException;
import com.serotonin.modbus4j.locator.BaseLocator;
import com.serotonin.modbus4j.locator.BinaryLocator;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import org.dsa.iot.dslink.node.Node;
import org.dsa.iot.dslink.node.Permission;
import org.dsa.iot.dslink.node.actions.Action;
import org.dsa.iot.dslink.node.actions.ActionResult;
import org.dsa.iot.dslink.node.actions.Parameter;
import org.dsa.iot.dslink.node.value.Value;
import org.dsa.iot.dslink.node.value.ValueType;
import org.dsa.iot.dslink.util.StringUtils;
import org.dsa.iot.dslink.util.handler.Handler;
import org.dsa.iot.dslink.util.json.JsonArray;
import org.dsa.iot.dslink.util.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/*
 * A regular class for the multiple tier design.
 * 
 * Link
 *     |
 *     ->Connection
 *                 |
 *                 ->Device Node
 * 
 * The Device Node and its connection  share the same node.
 * 
 * */

public class SlaveNode extends SlaveFolder {
	private static final Logger LOGGER;

	private static final int BITS_IN_REGISTER = 16;

	static {
		LOGGER = LoggerFactory.getLogger(SlaveNode.class);
	}

	long intervalInMs;

	Node statnode;

	private final ConcurrentMap<Node, Boolean> subscribed = new ConcurrentHashMap<>();
	final ConcurrentMap<Node, Long> lastUpdates = new ConcurrentHashMap<>();

	SlaveNode(ModbusConnection conn, Node node) {
		super(conn, node);

		conn.slaves.add(this);
		root = this;

		statnode = node.getChild(NODE_STATUS, true);
		if (statnode == null) {
			statnode = node.createChild(NODE_STATUS, true).setValueType(ValueType.STRING)
					.setValue(new Value(NODE_STATUS_SETTING_UP)).build();
		}

		init();
	}

	void init() {
		checkDeviceConnected();

		this.intervalInMs = node.getAttribute(ModbusConnection.ATTR_POLLING_INTERVAL).getNumber().longValue();

		makeEditAction();
	}

	void addToSub(Node event) {
		subscribed.put(event, true);
	}

	void removeFromSub(Node event) {
		subscribed.remove(event);
	}

	Set<Node> getSubscribed() {
		return subscribed.keySet();
	}

	boolean noneSubscribed() {
		return subscribed.isEmpty();
	}

	void makeEditAction() {
		Action act = new Action(Permission.READ, new EditHandler());
		act.addParameter(new Parameter(ModbusConnection.ATTR_SLAVE_NAME, ValueType.STRING, new Value(node.getName())));
		act.addParameter(new Parameter(ModbusConnection.ATTR_SLAVE_ID, ValueType.NUMBER,
				node.getAttribute(ModbusConnection.ATTR_SLAVE_ID)));
		double defint = node.getAttribute(ModbusConnection.ATTR_POLLING_INTERVAL).getNumber().doubleValue() / 1000;
		act.addParameter(new Parameter(ModbusConnection.ATTR_POLLING_INTERVAL, ValueType.NUMBER, new Value(defint)));
		act.addParameter(new Parameter(ModbusConnection.ATTR_ZERO_ON_FAILED_POLL, ValueType.BOOL,
				node.getAttribute(ModbusConnection.ATTR_ZERO_ON_FAILED_POLL)));
		act.addParameter(new Parameter(ModbusConnection.ATTR_USE_BATCH_POLLING, ValueType.BOOL,
				node.getAttribute(ModbusConnection.ATTR_USE_BATCH_POLLING)));
		act.addParameter(new Parameter(ModbusConnection.ATTR_CONTIGUOUS_BATCH_REQUEST_ONLY, ValueType.BOOL,
				node.getAttribute(ModbusConnection.ATTR_CONTIGUOUS_BATCH_REQUEST_ONLY)));
		double defdur = node.getAttribute(ModbusConnection.ATTR_SUPPRESS_NON_COV_DURATION).getNumber().doubleValue()
				/ 1000;
		act.addParameter(
				new Parameter(ModbusConnection.ATTR_SUPPRESS_NON_COV_DURATION, ValueType.NUMBER, new Value(defdur))
						.setDescription("how many seconds to wait before sending an update for an unchanged value"));
		String pingType = ModbusConnection.ATTR_PING_TYPE_HOLDING;
		int pingRegister = 0;
		if (node.getAttribute(ModbusConnection.ATTR_PING_TYPE) != null) {
			pingType = node.getAttribute(ModbusConnection.ATTR_PING_TYPE).getString();
			pingRegister = node.getAttribute(ModbusConnection.ATTR_PING_REGISTER).getNumber().intValue();
		}
		act.addParameter(
				new Parameter(ModbusConnection.ATTR_PING_TYPE, ValueType.makeEnum(Util.enumNames(PingType.class)),
						new Value(pingType)));
		act.addParameter(new Parameter(ModbusConnection.ATTR_PING_REGISTER, ValueType.NUMBER, new Value(pingRegister)));

		Node anode = node.getChild(ACTION_EDIT, true);
		if (anode == null)
			node.createChild(ACTION_EDIT, true).setAction(act).build().setSerializable(false);
		else
			anode.setAction(act);
	}

	@Override
	protected void remove() {
		super.remove();

		conn.slaves.remove(this);
	}

	private class EditHandler implements Handler<ActionResult> {
		public void handle(ActionResult event) {
			String name = event.getParameter(ATTR_NAME, ValueType.STRING).getString();
			int slaveid = event.getParameter(ModbusConnection.ATTR_SLAVE_ID, ValueType.NUMBER).getNumber().intValue();
			intervalInMs = (long) (event.getParameter(ModbusConnection.ATTR_POLLING_INTERVAL, ValueType.NUMBER)
					.getNumber().doubleValue() * 1000);
			boolean zerofail = event.getParameter(ModbusConnection.ATTR_ZERO_ON_FAILED_POLL, ValueType.BOOL).getBool();
			boolean batchpoll = event.getParameter(ModbusConnection.ATTR_USE_BATCH_POLLING, ValueType.BOOL).getBool();
			boolean contig = event.getParameter(ModbusConnection.ATTR_CONTIGUOUS_BATCH_REQUEST_ONLY, ValueType.BOOL)
					.getBool();
			long suppressDuration = (long) (event
					.getParameter(ModbusConnection.ATTR_SUPPRESS_NON_COV_DURATION, ValueType.NUMBER).getNumber()
					.doubleValue() * 1000);
			String pingType = event.getParameter(ModbusConnection.ATTR_PING_TYPE, ValueType.STRING).getString();
			int pingRegister = event.getParameter(ModbusConnection.ATTR_PING_REGISTER, ValueType.NUMBER).getNumber()
					.intValue();
			node.setAttribute(ModbusConnection.ATTR_SLAVE_ID, new Value(slaveid));
			node.setAttribute(ModbusConnection.ATTR_POLLING_INTERVAL, new Value(intervalInMs));
			node.setAttribute(ModbusConnection.ATTR_ZERO_ON_FAILED_POLL, new Value(zerofail));
			node.setAttribute(ModbusConnection.ATTR_USE_BATCH_POLLING, new Value(batchpoll));
			node.setAttribute(ModbusConnection.ATTR_CONTIGUOUS_BATCH_REQUEST_ONLY, new Value(contig));
			node.setAttribute(ModbusConnection.ATTR_SUPPRESS_NON_COV_DURATION, new Value(suppressDuration));
			node.setAttribute(ModbusConnection.ATTR_PING_TYPE, new Value(pingType));
			node.setAttribute(ModbusConnection.ATTR_PING_REGISTER, new Value(pingRegister));

			conn.getLink().handleEdit(root);

			if (!name.equals(node.getName())) {
				rename(name);
			} else {
				checkDeviceConnected();
				makeEditAction();
			}
		}
	}

	public void readPoints() {
		if (getMaster() == null) {
			return;
		}

		if (!NODE_STATUS_READY.equals(statnode.getValue().getString())) {
			checkDeviceConnected();
			if (!NODE_STATUS_READY.equals(statnode.getValue().getString())) {
				return;
			}
		}

		int id = Util.getIntValue(node.getAttribute(ModbusConnection.ATTR_SLAVE_ID));
		if (node.getAttribute(ModbusConnection.ATTR_USE_BATCH_POLLING).getBool()) {
			BatchRead<Node> batch = new BatchRead<>();
			batch.setContiguousRequests(
					node.getAttribute(ModbusConnection.ATTR_CONTIGUOUS_BATCH_REQUEST_ONLY).getBool());
			batch.setErrorsInResults(true);
			Set<Node> polled = new HashSet<>();
			for (Node pnode : subscribed.keySet()) {
				BaseLocator<?> locator = getLocator(id, pnode);
				if (locator == null) {
					continue;
				}
				batch.addLocator(pnode, locator);
				polled.add(pnode);
			}

			try {
				BatchResults<Node> response;
				synchronized (conn.masterLock) {
					if (getMaster() == null) {
						return;
					}
					response = getMaster().send(batch);
				}

				if (response == null) {
					return;
				}
				for (Node pnode : polled) {
					Object obj = response.getValue(pnode);
					updateValue(pnode, obj);
				}

			} catch (ModbusTransportException | ErrorResponseException e) {
				LOGGER.warn("error during batch poll: " + e.getMessage());
				LOGGER.debug("error during batch poll: ", e);
				checkDeviceConnected();
				if (node.getAttribute(ModbusConnection.ATTR_ZERO_ON_FAILED_POLL).getBool()) {
					for (Node pnode : polled) {
						if (pnode.getValueType().compare(ValueType.NUMBER)) {
							pnode.setValue(new Value(0));
						} else if (pnode.getValueType().compare(ValueType.BOOL)) {
							pnode.setValue(new Value(false));
						}
					}
				}
			}
		} else {
			for (Node pnode : subscribed.keySet()) {
				BaseLocator<?> locator = getLocator(id, pnode);
				if (locator == null) {
					continue;
				}

				try {
					Object obj;
					synchronized (conn.masterLock) {
						if (getMaster() == null) {
							return;
						}
						obj = getMaster().getValue(locator);
					}

					if (obj == null) {
						return;
					}
					updateValue(pnode, obj);

				} catch (ModbusTransportException | ErrorResponseException e) {
					LOGGER.warn("error during poll: " + e.getMessage());
					LOGGER.debug("error during poll: ", e);
					checkDeviceConnected();
					if (node.getAttribute(ModbusConnection.ATTR_ZERO_ON_FAILED_POLL).getBool()) {
						if (pnode.getValueType().compare(ValueType.NUMBER)) {
							pnode.setValue(new Value(0));
						} else if (pnode.getValueType().compare(ValueType.BOOL)) {
							pnode.setValue(new Value(false));
						}
					}
				}

			}
		}
	}

	private static BaseLocator<?> getLocator(int slaveId, Node pnode) {
		if (pnode.getAttribute(ATTR_OFFSET) == null)
			return null;
		PointType type = PointType.valueOf(pnode.getAttribute(ATTR_POINT_TYPE).getString());
		int offset = Util.getIntValue(pnode.getAttribute(ATTR_OFFSET));
		int numRegs = Util.getIntValue(pnode.getAttribute(ATTR_NUMBER_OF_REGISTERS));
		int bit = Util.getIntValue(pnode.getAttribute(ATTR_BIT));
		DataType dataType = DataType.valueOf(pnode.getAttribute(ATTR_DATA_TYPE).getString());

		Integer dt = DataType.getDataTypeInt(dataType);
		if (dt == null)
			dt = com.serotonin.modbus4j.code.DataType.FOUR_BYTE_INT_SIGNED;
		int range = PointType.getPointTypeInt(type);

		if (dataType == DataType.BOOLEAN && !BinaryLocator.isBinaryRange(range) && bit < 0) {
			dt = com.serotonin.modbus4j.code.DataType.TWO_BYTE_INT_SIGNED;
		}

		BaseLocator<?> locator = BaseLocator.createLocator(slaveId, range, offset, dt, bit, numRegs);
		return locator;
	}

	private static boolean isBitSet(int num, int bit) {
		return ((num >> bit) & 1) == 1;
	}

	private static int parseIntModulo10K(int registerContents, boolean swap) {
		short highRegister = (short) (registerContents >>> BITS_IN_REGISTER);
		short lowRegister = (short) (registerContents & 0xffff);
		if (swap)
			return ((int) lowRegister) * 10000 + (int) highRegister;
		else
			return ((int) highRegister) * 10000 + (int) lowRegister;
	}

	private static long parseUnsignedIntModulo10K(int registerContents, boolean swap) {
		short highRegister = (short) (registerContents >>> BITS_IN_REGISTER);
		short lowRegister = (short) (registerContents & 0xffff);
		long num;
		if (swap)
			num = Util.toUnsignedLong(Util.toUnsignedInt(lowRegister) * 10000 + Util.toUnsignedInt(highRegister));
		else
			num = Util.toUnsignedLong(Util.toUnsignedInt(highRegister) * 10000 + Util.toUnsignedInt(lowRegister));
		return num;
	}

	private void updateValue(Node pnode, Object obj) {
		DataType dataType = DataType.valueOf(pnode.getAttribute(ATTR_DATA_TYPE).getString());
		double scaling = Util.getDoubleValue(pnode.getAttribute(ATTR_SCALING));
		double addscale = Util.getDoubleValue(pnode.getAttribute(ATTR_SCALING_OFFSET));

		ValueType vt = null;
		Value v = null;
		if (DataType.getDataTypeInt(dataType) != null) {
			if (dataType == DataType.BOOLEAN && obj instanceof Boolean) {
				vt = ValueType.BOOL;
				v = new Value((Boolean) obj);
			} else if (dataType == DataType.BOOLEAN && obj instanceof Number) {
				vt = ValueType.ARRAY;
				JsonArray jarr = new JsonArray();
				for (int i = 0; i < BITS_IN_REGISTER; i++) {
					jarr.add(isBitSet(((Number) obj).intValue(), i));
				}
				v = new Value(jarr);
			} else if (dataType.isString() && obj instanceof String) {
				vt = ValueType.STRING;
				v = new Value((String) obj);
			} else if (obj instanceof Number) {
				vt = ValueType.NUMBER;
				Number num = (Number) obj;
				v = new Value(num.doubleValue() / scaling + addscale);
			} else if (obj instanceof ExceptionResult) {
				ExceptionResult result = (ExceptionResult) obj;
				LOGGER.error(pnode.getName() + " : " + result.getExceptionMessage());
			}
		} else {
			switch (dataType) {
			case INT32M10KSWAP:
			case INT32M10K: {
				int registerContents = ((Number) obj).intValue();
				boolean swap = (dataType == DataType.INT32M10KSWAP);
				int num = parseIntModulo10K(registerContents, swap);
				vt = ValueType.NUMBER;
				v = new Value(num / scaling + addscale);
				break;
			}
			case UINT32M10KSWAP:
			case UINT32M10K: {
				int registerContents = ((Number) obj).intValue();
				boolean swap = (dataType == DataType.UINT32M10KSWAP);
				long num = parseUnsignedIntModulo10K(registerContents, swap);
				vt = ValueType.NUMBER;
				v = new Value(num / scaling + addscale);
				break;
			}
			default:
				vt = null;
				v = null;
				break;
			}
		}
		
		if (v == null && node.getAttribute(ModbusConnection.ATTR_ZERO_ON_FAILED_POLL).getBool()) {
			if (pnode.getValueType().compare(ValueType.NUMBER)) {
				v = new Value(0);
			} else if (pnode.getValueType().compare(ValueType.BOOL)) {
				v = new Value(false);
			}
		}
		
		if (vt != null && !vt.equals(pnode.getValueType())) {
			pnode.setValueType(vt);
		}
		if (v != null && (!v.equals(pnode.getValue()) || isTimeForNonCovUpdate(pnode))) {
			pnode.setValue(v);
			lastUpdates.put(pnode, System.currentTimeMillis());
		}
	}
	
	private boolean isTimeForNonCovUpdate(Node pnode) {
		long suppressDuration = node.getAttribute(ModbusConnection.ATTR_SUPPRESS_NON_COV_DURATION).getNumber().longValue();
		if (suppressDuration == 0) {
			return true;
		}
		Long lastUpdate = lastUpdates.get(pnode);
		if (lastUpdate == null) {
			return true;
		}
		long now = System.currentTimeMillis();
		return now - lastUpdate > suppressDuration;
	}

	public ScheduledThreadPoolExecutor getDaemonThreadPool() {
		return conn.getDaemonThreadPool();
	}

	@Override
	public ModbusMaster getMaster() {
		return conn.master;
	}

	@Override
	public Node getStatusNode() {
		return this.statnode;
	}

	@Override
	void checkDeviceConnected() {
		int slaveId = node.getAttribute(ATTR_SLAVE_ID).getNumber().intValue();
		Value pingType = node.getAttribute(ModbusConnection.ATTR_PING_TYPE);
		Value slaveOffset = node.getAttribute(ModbusConnection.ATTR_PING_REGISTER);

		synchronized (conn.masterLock) {
			boolean connected = false;
			if (conn.master != null) {
				try {
					if (pingType != null && slaveOffset != null) {
						connected = Util.pingModbusSlave(conn.master, slaveId, pingType.getString().toString(),
								slaveOffset.getNumber().intValue());
					} else
						connected = Util.pingModbusSlave(conn.master, slaveId);
				} catch (Exception e) {
					LOGGER.debug("error during device ping: ", e);
				}
				if (connected) {
					statnode.setValue(new Value(NODE_STATUS_READY));
				} else {
					statnode.setValue(new Value(NODE_STATUS_PING_FAILED));
					conn.checkConnection();
				}
			} else {
				statnode.setValue(new Value(NODE_STATUS_CONN_DOWN));
			}
		}
	}

	@Override
	protected void duplicate(String name) {
		JsonObject jobj = conn.getLink().serializer.serialize();
		JsonObject parentobj = getParentJson(jobj);
		JsonObject nodeobj = parentobj.get(node.getName());
		parentobj.put(StringUtils.encodeName(name), nodeobj);
		conn.getLink().deserializer.deserialize(jobj);
		Node newnode = node.getParent().getChild(name, true);

		SlaveNode sn = new SlaveNode(conn, newnode);
		sn.restoreLastSession();
	}
}
