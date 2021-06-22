package com.wp.csmu.classschedule.network.service

import android.util.Log
import com.wp.csmu.classschedule.application.MyApplicationLike
import com.wp.csmu.classschedule.exception.InvalidPasswordException
import com.wp.csmu.classschedule.network.LoginState
import com.wp.csmu.classschedule.network.NetworkConfig
import com.wp.csmu.classschedule.network.login.LoginClient
import com.wp.csmu.classschedule.view.bean.Score
import com.wp.csmu.classschedule.view.bean.ScoreFilterBean
import com.wp.csmu.classschedule.view.scheduletable.Subjects
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import retrofit2.Retrofit
import java.lang.Exception

object ServiceClient {
    private val client = OkHttpClient.Builder()
            .addInterceptor {
                val request = it.request()
                if (NetworkConfig.cookie == "") {
                    val state = runBlocking { LoginClient.login(MyApplicationLike.user.account!!, MyApplicationLike.user.password!!) }
                    if (state == LoginState.WRONG_PASSWORD) {
                        throw InvalidPasswordException()
                    }
                }
                val newRequest = request.newBuilder()
                        .removeHeader("user-agent")
                        .addHeader("user-agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/89.0.4389.90 Safari/537.36")
                        .removeHeader("cookie")
                        .addHeader("cookie", NetworkConfig.cookie)
                        .removeHeader("referer")
                        .addHeader("referer", request.url.toString())
                        .build()
                return@addInterceptor it.proceed(newRequest)
            }.build()
    private val retrofit = Retrofit.Builder()
            .client(client)
            .baseUrl(ServiceApi.BASE_URL)
            .build()
            .create(ServiceApi::class.java)

    // 获取开学时间
    suspend fun getTermBeginsTime(termId: String = ""): String {
        val html = retrofit.getTermBeginsTime(termId).string()
        val document = Jsoup.parse(html)
        val time = document.select("#kbtable > tbody > tr:nth-child(2) > td:nth-child(2)").attr("title")
        return time.replace("年", "-").replace("月", "-")
    }

    /**
     * 获取学期id
     * @return HashMap<Pair<String, String>, Boolean> （学期id - 学期名称）- 是否选中
     */
    suspend fun getTermId(): LinkedHashMap<Pair<String, String>, Boolean> {
        val html = retrofit.getTermBeginsTime().string()
        val document = Jsoup.parse(html)
        val selectHtml = document.select("#xnxq01id > option")
        val map = LinkedHashMap<Pair<String, String>, Boolean>()
        selectHtml.forEach {
            val termId = it.attr("value")
            val termName = it.text()
            val pair = Pair(termId, termName)
            val selected = (it.attr("selected") == "selected")
            map[pair] = selected
        }
        return map
    }


    private suspend fun getGradeQueryPageSource(): String = retrofit.getGradeQuery().string()

    suspend fun getGradeQueryFilter(): ScoreFilterBean {
        val html = getGradeQueryPageSource()
        val termId = getExamTermId(html)
        val courseXZ = getCourseXZ(html)
        val displayMode = getDisplayMode(html)
        return ScoreFilterBean(termId, courseXZ, displayMode)
    }

    /**
     * 获取学期id,考试页面
     * @return HashMap<Pair<String, String>, Boolean> （学期id - 学期名称）- 是否选中
     */
    private fun getExamTermId(pageSource: String): LinkedHashMap<Pair<String, String>, Boolean> {
        val document = Jsoup.parse(pageSource)
        val data = LinkedHashMap<Pair<String, String>, Boolean>()
        val option = document.select("#kksj > option")
        for (op in option) {
            val id = op.attr("value")
            val text = op.text()
            val selected = op.hasAttr("selected")
            data[Pair<String, String>(id, text)] = selected;
        }
        return data
    }

    /**
     * 获取课程性质,考试页面
     * @return LinkedHashMap<String,String> 选项id - 选项名称
     */
    private fun getCourseXZ(pageSource: String): LinkedHashMap<Pair<String, String>, Boolean> {
        val document = Jsoup.parse(pageSource)
        val data = LinkedHashMap<Pair<String, String>, Boolean>()
        val option = document.select("#kcxz > option")
        data[Pair("", "全部课程")] = true // 默认显示全部性质课程
        for (op in option) {
            val id = op.attr("value")
            if (id == "") {
                continue
            }
            val text = op.text()
            data[Pair(id, text)] = false
        }
        return data
    }

    /**
     * 显示方式，考试页面
     * @param pageSource String
     * @return LinkedHashMap<Pair<String,String>,Boolean>
     */
    private fun getDisplayMode(pageSource: String): LinkedHashMap<Pair<String, String>, Boolean> {
        val document = Jsoup.parse(pageSource)
        val data = LinkedHashMap<Pair<String, String>, Boolean>()
        val option = document.select("#xsfs > option")
        for (op in option) {
            val id = op.attr("value")
            val text = op.text()
            data[Pair(id, text)] = id == "all" // 默认显示全部成绩
        }
        return data
    }

    suspend fun getScheduleList(termId: String = ""): HashSet<Subjects> {
        val schedules = HashSet<Subjects>()
        val html = retrofit.getSchedule(termId).string()
        val document = Jsoup.parse(html)
        val scheduleTableHtml = document.select("#kbtable > tbody > tr")
        scheduleTableHtml.drop(1)
                .forEach { rowHtml ->
                    rowHtml.select("td > div.kbcontent").forEachIndexed { dayIndex, cellHtml ->
                        val text = cellHtml.outerHtml().replace(Regex("-{3,}"), "</div><div>")
                        Jsoup.parse(text).select("div")?.forEach innerForEach@{ scheduleHtml ->
                            // 课程名称
                            val scheduleName = scheduleHtml.ownText()
                            if (scheduleName == "") {
                                return@innerForEach
                            }
                            // 老师名字
                            val teacherName = scheduleHtml.select("font[title=老师]")?.text() ?: ""
                            // 星期，上课节次
                            val weeksTimesText = scheduleHtml.select("font[title=周次(节次)]").text()
                                    ?: ""
                            val weeks = parseWeeks(weeksTimesText)
                            val times = parseTimes(weeksTimesText)
                            // 教室
                            val classRoom = scheduleHtml.select("font[title=教室]").text() ?: ""

                            val subject = Subjects().also {
                                it.day = dayIndex + 1
                                it.name = scheduleName
                                it.room = classRoom
                                it.start = times.first()
                                it.step = times.size
                                it.teacher = teacherName
                                it.weeks = weeks
                            }
                            schedules.add(subject)
                        }
                    }
                }
        return schedules
    }

    private fun parseWeeks(string: String): ArrayList<Int> {
        if (string == "") {
            return arrayListOf(0)
        }
        val weekText = (Regex("(.*)(?=\\(周\\))").find(string)?.value) ?: return arrayListOf()
        val weeksList = ArrayList<Int>()
        weekText.split(",").forEach {
            it.split("-").apply {
                weeksList.addAll(this.first().toInt()..this.last().toInt())
            }
        }
        return weeksList
    }

    private fun parseTimes(string: String): ArrayList<Int> {
        if (string == "") {
            return arrayListOf(0)
        }
        val timeText = (Regex("(?<=\\[).*(?=节])").find(string)?.value) ?: return arrayListOf()
        val weekList = ArrayList<Int>()
        timeText.split("-").apply {
            weekList.addAll(this.first().toInt()..this.last().toInt())
        }
        return weekList
    }

    suspend fun queryGrades(termId: String, courseXZ: String, displayMode: String): LinkedHashSet<Score> {
        val html = retrofit.getGrades(
                term = termId,
                classAttr = courseXZ,
                orderBy = displayMode
        ).string()
        val document = Jsoup.parse(html)
        val gradeTableHtml = document.select("#dataList > tbody > tr")
        if (gradeTableHtml.size == 2 && gradeTableHtml[1].text() == "未查询到数据") {
            return LinkedHashSet()
        }
        val scores = LinkedHashSet<Score>()
        gradeTableHtml.drop(1).forEach { rowHtml ->
            val tds = rowHtml.select("td")
            val term = tds[1].text()
            val name = tds[3].text()
            val score = tds[4].text()
            val skillScores = tds[5].text()
            val performanceScore = tds[6].text()
            val knowledgePoints = tds[7].text()
            val credits = tds[9].text().toDouble()
            val examAttribute = tds[10].text()
            val subjectNature = tds[14].text()
            val subjectAttribute = tds[15].text()
            scores.add(Score(term, name, score, skillScores, performanceScore, knowledgePoints, credits, examAttribute, subjectNature, subjectAttribute))
        }
        return scores
    }

    suspend fun queryAllXZGrades(termId: String, courseXZ: ArrayList<String>, displayMode: String): LinkedHashSet<Score> {
        val data = LinkedHashSet<Score>()
        for (c in courseXZ) {
            try {
                data.addAll(queryGrades(termId, c, displayMode))
            }catch (e:Exception){
                Log.e("ServiceClient","课程性质$c 查询失败")
                e.printStackTrace()
            }
        }
        return data
    }
}