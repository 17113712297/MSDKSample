package com.example.msdksample.logs

/** 操作日志只收低频业务信令，不记录连续速度帧、心跳及分页数据。 */
object OperationCommandNames {
    private val names = mapOf(
        0x01 to "自动起飞", 0x02 to "自动降落", 0x03 to "悬停", 0x04 to "连续速度",
        0x11 to "云台跟随", 0x12 to "云台角度", 0x21 to "相机模式", 0x22 to "拍照",
        0x23 to "录像控制", 0x24 to "录像配置", 0x25 to "镜头/变焦", 0x31 to "补光灯",
        0x41 to "记录航点", 0x42 to "保存航线", 0x43 to "清除航点",
        0x50 to "起飞前检查", 0x51 to "视觉降落",
        0x60 to "设置地图名称", 0x61 to "开始建图", 0x62 to "保存地图", 0x63 to "结束建图",
        0x65 to "应用采点地图", 0x66 to "设置航点名称", 0x67 to "开始采点",
        0x68 to "生成2D地图", 0x69 to "生成像素坐标", 0x6A to "结束采点",
        0x6B to "应用巡航地图", 0x6C to "应用巡航航线", 0x6D to "开始巡航",
        0x6F to "选择航线", 0x70 to "设置服务器", 0x71 to "设置云台俯仰",
        0x72 to "修改配置", 0x73 to "读取配置", 0x74 to "重启机载服务",
        0x75 to "暂停任务", 0x76 to "继续任务", 0x77 to "返航",
        0x81 to "起飞完成通知", 0x82 to "降落完成通知", 0x83 to "悬停完成通知",
        0x84 to "预检通过通知", 0x85 to "预检失败通知"
    )
    fun name(cmd: Byte): String = names[cmd.toInt() and 0xFF] ?: "指令 0x${(cmd.toInt() and 0xFF).toString(16).uppercase()}"
    fun shouldRecord(cmd: Byte): Boolean = (cmd.toInt() and 0xFF).let { it in names && it != 0x04 }
}
