package modbus;

public enum PingType {
	HOLDING, INPUT;

	static int parseType(String typestr) {
		int typeint = 0;
		switch (typestr.toUpperCase()) {
			case ModbusConnection.ATTR_PING_TYPE_HOLDING:
				break;
			case ModbusConnection.ATTR_PING_TYPE_INPUT:
				typeint = 1;
				break;
			default:
				break;
		}
		return typeint;
	}
}
