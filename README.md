## 1.2、发送设备状态信息

http://ip:port/sendDeviceData?siteId=11&deviceId=1&file=file.json

协议属性说明：

| 属性名 | 类型 | 描述 |
| --- | --- | --- |
| siteId | int | 站点ID，需可设置，默认11 |
| deviceId | int | 设备ID，需可设置，默认1 |
| file | 文件 | 设备状态JSON文件 |

file 中设备状态JSON文件中字段格式说明：（设备状态需可设置(30秒)时间发送一次）

| 属性名 | 类型 | 描述 |
| --- | --- | --- |
| uavState | int | 无人机状态：1在线，0离线 |
| controlState | int | 遥控器状态：1在线，0离线 |
| controlSoc | double | 遥控器电量：0.0%-100.0%，精度0.00 |
| controlRssi | double | 遥控器信号强度：0.0%-100.0%，精度0.00 |
| batteryTemp | double | 无人机电池温度,，单位℃，精度0.00 |
| batterySoc | double | 无人机电池电量：0.00%-100.00%，精度0.00 |
| batteryRssi | double | 无人机信号强度：0.0%-100.0%，精度0.00 |
| batteryVolt | double | 无人机电池电压，单位V，精度0.00 |
| batteryCycleNum | int | 无人机电池循环次数 |
|  |  | (如果有其他类型，后续继续添加) |

JSON示例：

| { "uavState": 1, "controlState": 1, "controlSoc": 90.1, "controlRssi": 99.9, "batteryTemp": 50.5, "batterySoc": 90.9, "batteryRssi": 99.9, "batteryVolt": 40.1, "batteryCycleNum": 1} |
| --- |

返回值对象属性说明：

| 属性名 | 类型 | 描述 |
| --- | --- | --- |
| resultCode | Integer | 请求结果:1 成功，2 失败，3 数据格式不正确 |

返回值示例：

| { "resultCode": 1 } |
| --- |heihiehiehi二的丰富2s而奋斗
