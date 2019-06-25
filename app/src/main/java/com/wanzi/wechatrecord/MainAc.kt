package com.wanzi.wechatrecord

import android.Manifest
import android.app.Activity
import android.content.*
import android.support.v7.app.AppCompatActivity
import android.os.Bundle
import com.afollestad.materialdialogs.MaterialDialog
import com.wanzi.wechatrecord.util.ShellCommand
import android.databinding.DataBindingUtil
import android.os.Handler
import android.view.KeyEvent
import android.widget.Toast
import com.wanzi.wechatrecord.databinding.AcMainBinding
import com.wanzi.wechatrecord.util.LogUtils
import com.tbruyelle.rxpermissions2.RxPermissions
import com.wanzi.wechatrecord.entry.Message
import com.wanzi.wechatrecord.util.FileUtils
import com.wanzi.wechatrecord.util.TimeUtils
import net.sqlcipher.database.SQLiteDatabase
import net.sqlcipher.database.SQLiteDatabaseHook
import org.litepal.crud.DataSupport
import java.io.File
import java.io.RandomAccessFile
import java.lang.Long
import java.text.SimpleDateFormat
import java.util.*

class MainAc : AppCompatActivity() {

    private lateinit var binding: AcMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = DataBindingUtil.setContentView(this, R.layout.ac_main)

        // 检查权限
        val rxPermissions = RxPermissions(this)
        rxPermissions
                .request(
                        Manifest.permission.READ_PHONE_STATE,
                        Manifest.permission.READ_EXTERNAL_STORAGE,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE,
                        Manifest.permission.RECEIVE_BOOT_COMPLETED
                )
                .subscribe {
                    if (!it) {
                        toast("请打开相关权限")
                        // 如果权限申请失败，则退出
                        android.os.Process.killProcess(android.os.Process.myPid())
                    }
                }

        binding.btn.setOnClickListener {
            checkRoot()
        }
    }


    override fun onDestroy() {
        super.onDestroy()
        CustomApplication.getRefWatcher(this).watch(this)
    }
    private val currApkPath = "/storage/emulated/0/"
    private val COPY_WX_DATA_DB = "wx_data.db"
    private fun checkRoot() {

        try {
            log("准备检测Root权限")
            // 检测是否拥有Root权限
            if (!ShellCommand.checkRoot(packageCodePath)) {
                log("检测到未拥有Root权限")
                // 申请Root权限（弹出申请root权限框）
                val rootCommand = "chmod 777 $packageCodePath"
                ShellCommand.shellCommand(rootCommand)

                MaterialDialog.Builder(this)
                        .title("提示")
                        .content("请授予本APP Root权限")
                        .positiveText("确定")
                        .onPositive { _, _ ->
                            goMIUIPermission()
                        }
                        .show()
            } else {
                startCopy()
            }
        } catch (e: Exception) {
            toast("检查Root权限失败：${e.message!!}")
        }

    }

    private fun startCopy() {

        val context = this.applicationContext
        /**
        - 异步线程
         */
        Thread(object : Runnable{
            override fun run() {
                val copyFilePath = currApkPath + COPY_WX_DATA_DB
                SQLiteDatabase.loadLibs(context)
                val hook = object : SQLiteDatabaseHook {
                    override fun preKey(database: SQLiteDatabase) {}

                    override fun postKey(database: SQLiteDatabase) {
                        database.rawExecSQL("PRAGMA cipher_migrate;") // 兼容2.0的数据库
                    }
                }
                try {
                    log("打开数据库。。。")
                    // 打开数据库连接
                    val db = SQLiteDatabase.openOrCreateDatabase(File(copyFilePath), "b3a1248", null, hook)

                    openMessageTable(db)
                    db.close()
                } catch (e: Exception) {
                    log("打开数据库失败：${e.message}")
//                    FileUtils.writeLog(this, "打开数据库失败：${e.message}\n")
                    toast("打开数据库失败：${e.message}")
                }
            }
        }).start()
    }

    private fun makeFilePath(filePath: String, fileName: String): File? {
        var file: File? = null
        makeRootDirectory(filePath)
        try {
            file = File(filePath + fileName)
            if (!file.exists()) {
                file.createNewFile()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return file
    }

    private fun makeRootDirectory(filePath: String) {
        var file: File? = null
        try {
            file = File(filePath)
            if (!file.exists()) {
                file.mkdir()
            }
        } catch (e: Exception) {
            log("error:" + e.toString() + "")
        }

    }

    // 打开聊天记录表
    private fun openMessageTable(db: SQLiteDatabase) {
        val filePath = "/storage/emulated/0/"
        val fileName = "chatchat.txt"
        // 一般公众号原始ID开头都是gh_
        val cursor = db.rawQuery("select * from message where talker not like 'gh_%' and msgid > ? order by createTime asc", arrayOf("0"))
        if (cursor.count > 0) {
            while (cursor.moveToNext()) {
                val msgSvrId = cursor.getString(cursor.getColumnIndex("msgSvrId"))
                val type = cursor.getString(cursor.getColumnIndex("type"))
                val isSend = cursor.getString(cursor.getColumnIndex("isSend"))
                val createTime = cursor.getLong(cursor.getColumnIndex("createTime"))
                val talker = cursor.getString(cursor.getColumnIndex("talker"))
                var content = cursor.getString(cursor.getColumnIndex("content"))
                if (content == null) content = ""
                var imgPath = cursor.getString(cursor.getColumnIndex("imgPath"))
                if (imgPath == null) imgPath = ""
                // 根据“msgSvrId”来判断聊天记录唯一性
                if (msgSvrId == null) {
                    log("该次记录 msgSvrId 为空，跳过")
                    continue
                }
                val list = DataSupport.where("msgSvrId = ?", msgSvrId).find(Message::class.java)
                if (list.isEmpty()) {

                    if (type == "1" && talker == "2582335099@chatroom") {
                        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                        val sd = sdf.format(Date(Long.parseLong(createTime.toString())))
                        log("聊天文字：$sd : $content")
                        writeTxtToFile(sd + ":" + content, filePath, fileName)
                    }

                }
            }
        }
        cursor.close()
    }

    private fun writeTxtToFile(strcontent: String, filePath: String, fileName: String) {
        //生成文件夹之后，再生成文件，不然会出错
        makeFilePath(filePath, fileName)

        val strFilePath = filePath + fileName
        // 每次写入时，都换行写
        val strContent = strcontent + "\r\n"
        try {
            val file = File(strFilePath)
            if (!file.exists()) {
                log("TestFile" + " Create the file:$strFilePath")
                file.parentFile.mkdirs()
                file.createNewFile()
            }
            val raf = RandomAccessFile(file, "rwd")
            raf.seek(file.length())
            raf.write(strContent.toByteArray())
            raf.close()
        } catch (e: Exception) {
            log("TestFile" + " Error on write File:$e")
        }

    }



    /**
     * 跳转到MIUI的权限管理页面
     */
    private fun goMIUIPermission() {
        startCopy()
//        val i = Intent("miui.intent.action.APP_PERM_EDITOR")
//        val componentName = ComponentName("com.miui.securitycenter", "com.miui.permcenter.MainAcitivty")
//        i.component = componentName
//        i.putExtra("extra_pkgname", packageName)
//        try {
//            startActivity(i)
//        } catch (e: Exception) {
//            toast("跳转权限管理页面失败：${e.message!!}")
//        }
    }

    private fun Activity.toast(text: CharSequence, duration: Int = Toast.LENGTH_SHORT) {
        Toast.makeText(this, text, duration).show()
    }

    fun log(msg: String) {
        LogUtils.i(this, msg)
    }

    /**
     * 返回键只返回桌面
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            moveTaskToBack(false)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
