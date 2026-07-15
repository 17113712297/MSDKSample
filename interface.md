#1.1航线信息：
GET /airlineInfo?siteId=11&deviceId=1&airlineKey=AAAA&detectTimeCur=20250701125959
| 属性名 | 类型 | 描述 |
| --- | --- | --- |
| siteId | int | 站点ID，需可设置，默认11 |
| deviceId | int | 设备ID，需可设置，默认1 |
| airlineKey | String | 航线唯一标识 |
| detectTimeCur | String | 检测时间，格式：20250701125959 |



#1.8 无人机端 HTTP 协议格式说明 - 文件上传

## 接口地址

`http://ip:port/upload2WRJ?file=20251021154142-pic-0001.jpg`

## 协议属性说明

| 属性名 | 类型 | 描述 |
| --- | --- | --- |
| file | 文件 | 文件名格式说明：`20251021154142-pic-0001.jpg`、`20251021154142-file-0001.txt`、`20251021154142-vcr-0001.mp4`<br><br>时间：`20251021154142`<br>类型：`pic` 图片，`file` 文件，`vcr` 录像<br>文件名：`0001.jpg` |

## 返回值格式

- `1`
- 或者
- `2`

## 返回值对象属性说明

> 注：图片中该表“属性名”列未显示具体字段名，以下按原图保留为空。

| 属性名 | 类型 | 描述 |
| --- | --- | --- |
|  | Integer | 请求结果：`1` 成功，`2` 失败。 |


## 1.11、上传视屏完成
http://ip:port/sendPicOver?siteId=11&deviceId=1&airlineKey =1&takeoffState=1&detectTimeCur=20250701125959
协议属性说明：
| 属性名 | 类型 | 描述 |
| --- | --- | --- |
| siteId | int | 站点ID，需可设置，默认11 |
| deviceId | int | 设备ID，需可设置，默认1 |
| airlineKey | String | 当前航线唯一标识 |
| detectTimeCur | String | 检测时间，格式：20250701125959 |
返回值对象属性说明：
| 属性名 | 类型 | 描述 |
| --- | --- | --- |
| resultCode | Integer | 请求结果:1 成功，2 失败，3 数据格式不正确 |
返回值格式：
| { "resultCode": 1 } |
| --- |



