package dev.goutou.wingman.config

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 诊断包：把排查要用的东西打成一个 zip。
 *
 * 为什么是 zip 而不是一个大 txt：几块互不相关的信息混在一起很难读，分开成
 * 「说明 / 环境 / 配置 / 角色 / 诊断 / 最近一次调用 / 用量」才像一份能直接转发的报告。
 *
 * [zip] 是纯函数（进 map、出字节），所以能在 JVM 单测里直接验。
 */
object DiagExport {

    fun zip(files: Map<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            files.forEach { (name, text) ->
                // 固定时间戳：同样的输入出同样的字节（好 diff，也好测）
                z.putNextEntry(ZipEntry(name).apply { time = 0L })
                z.write(text.toByteArray(Charsets.UTF_8))
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /**
     * 说明文件放第一份：先告诉对方这里面有什么、发之前该看什么。
     * 这份文本会被单测钉住「必须提到聊天内容」——不能让用户稀里糊涂把聊天记录发出去。
     */
    fun readme(versionName: String): String = """TalkTact 诊断包（$versionName）

这个包里是排查问题要用的信息，已经按文件分好：
  01-环境.txt         系统版本、机型、是否低内存设备、玻璃效果档位
  02-配置.txt         接口地址、模型、生成模式等（**不含 API Key**）
  03-角色.txt         角色数量与每人的记录条数（**不含聊天内容**）
  04-诊断.txt         注入侧抓到的微信界面结构（「读不到消息」时最关键的一份）
  05-最近一次调用.txt  真正发出去的 system 提示词 + user 消息 + 模型原始返回
  06-用量.txt         调用次数与累计 token

⚠️ 发出去之前先自己看一眼：04 和 05 里会有你和对方的聊天内容。
   不想公开的部分删掉再发。
"""
}
