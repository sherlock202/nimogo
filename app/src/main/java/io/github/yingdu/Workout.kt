package io.github.yingdu

import android.content.Context
import android.os.PowerManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * 锻炼：手机上建运动组（一组里若干动作，每个动作有次数和每次时长），
 * 开始后眼镜上显示动作名、已完成次数，进度环按「每次多久」转一圈（和番茄钟同一个环）。
 * 规则（WorkoutLogic）不依赖 Android，方便单元测试。
 */

enum class WorkoutUnit(val label: String, val ms: Long) {
    SEC("秒", 1_000), MIN("分钟", 60_000);

    companion object {
        fun from(name: String) = values().firstOrNull { it.name == name } ?: SEC
    }
}

data class WorkoutMove(
    val id: String,
    val name: String,
    val reps: Int,
    val each: Int,
    val unit: WorkoutUnit,
) {
    fun line(): String = "${name.ifBlank { "动作" }} ${reps.coerceAtLeast(1)}次×$each${unit.label}"
}

data class WorkoutGroup(
    val id: String,
    val name: String,
    val rounds: Int,
    val rest: Int,
    val restUnit: WorkoutUnit,
    val moves: List<WorkoutMove>,
) {
    fun summary(): String {
        val body = if (moves.isEmpty()) "还没有动作" else moves.joinToString("、") { it.line() }
        val gap = if (rest <= 0) "动作之间不休息" else "间隔 ${rest}${restUnit.label}"
        return "$body · 循环 ${rounds.coerceAtLeast(1)} 次 · $gap"
    }
}

/** 开始时从运动组抄下来的一份，改组不会影响正在进行的这一轮。 */
data class WorkoutStep(val name: String, val reps: Int, val repMs: Long)

data class WorkoutPlan(
    val groupId: String,
    val title: String,
    val rounds: Int,
    val restMs: Long,
    val moves: List<WorkoutStep>,
)

enum class WorkoutPhase { REP, REST, DONE }

/**
 * @param rep 当前动作已经做完的次数（正在做的这一次还没算进去）
 * @param leftMs 暂停时还剩多少毫秒；-1 = 这一段还没开始（或刚进入）
 */
data class WorkoutRun(
    val plan: WorkoutPlan,
    val round: Int = 0,
    val move: Int = 0,
    val rep: Int = 0,
    val phase: WorkoutPhase = WorkoutPhase.REP,
    val running: Boolean = false,
    val endAt: Long = 0,
    val leftMs: Long = -1,
)

object WorkoutLogic {
    fun planOf(g: WorkoutGroup) = WorkoutPlan(
        g.id,
        g.name.ifBlank { "锻炼" }.take(20),
        g.rounds.coerceIn(1, 99),
        if (g.rest <= 0) 0L else g.rest.coerceIn(1, 999).toLong() * g.restUnit.ms,
        g.moves.map {
            WorkoutStep(it.name.ifBlank { "动作" }.take(20), it.reps.coerceIn(1, 999), it.each.coerceIn(1, 999).toLong() * it.unit.ms)
        },
    )

    fun fresh(plan: WorkoutPlan) = WorkoutRun(plan)

    fun sliceMs(s: WorkoutRun): Long = when (s.phase) {
        WorkoutPhase.REP -> s.plan.moves.getOrNull(s.move)?.repMs?.coerceAtLeast(1) ?: 0L
        WorkoutPhase.REST -> s.plan.restMs.coerceAtLeast(0)
        WorkoutPhase.DONE -> 0L
    }

    fun remaining(s: WorkoutRun, now: Long): Long = when {
        s.phase == WorkoutPhase.DONE -> 0L
        s.running -> (s.endAt - now).coerceAtLeast(0)
        s.leftMs >= 0 -> s.leftMs
        else -> sliceMs(s)
    }

    /** 这一段已经过去的比例 0..1。 */
    fun progress(s: WorkoutRun, now: Long): Double {
        val d = sliceMs(s)
        if (d <= 0L) return 1.0
        return (1.0 - remaining(s, now).toDouble() / d).coerceIn(0.0, 1.0)
    }

    fun start(s: WorkoutRun, now: Long): WorkoutRun {
        if (s.phase == WorkoutPhase.DONE || s.plan.moves.isEmpty()) return s.copy(phase = WorkoutPhase.DONE, running = false, leftMs = -1)
        if (s.running) return s
        return s.copy(running = true, endAt = now + remaining(s, now), leftMs = -1)
    }

    fun pause(s: WorkoutRun, now: Long): WorkoutRun =
        if (!s.running) s else s.copy(running = false, leftMs = remaining(s, now))

    /** 当前这一段结束，进入下一次 / 休息 / 下一个动作。新的一段先不计时。 */
    fun nextSlice(s: WorkoutRun): WorkoutRun {
        if (s.phase == WorkoutPhase.DONE) return s
        val plan = s.plan
        val step = plan.moves.getOrNull(s.move) ?: return done(s)
        val n = when (s.phase) {
            WorkoutPhase.REST -> goNext(s)
            WorkoutPhase.REP -> {
                val reps = step.reps.coerceAtLeast(1)
                if (s.rep + 1 < reps) s.copy(rep = s.rep + 1) else afterMove(s.copy(rep = reps))
            }
            WorkoutPhase.DONE -> s
        }
        return n.copy(running = false, endAt = 0, leftMs = -1)
    }

    /** 放弃这个动作剩下的次数：有休息就先休息，否则直接下一个。 */
    fun skipMove(s: WorkoutRun): WorkoutRun {
        if (s.phase == WorkoutPhase.DONE || s.plan.moves.isEmpty()) return s
        val n = if (s.phase == WorkoutPhase.REST) goNext(s)
        else afterMove(s.copy(rep = s.plan.moves.getOrNull(s.move)?.reps?.coerceAtLeast(1) ?: s.rep))
        return n.copy(running = false, endAt = 0, leftMs = -1)
    }

    /**
     * 到点之后对齐。只晚了一点：下一段从现在起给足整段时间。
     * 晚过了整整一段以上：中间整段算做完，正在进行的那一段重新给足时间。
     */
    fun catchUp(s: WorkoutRun, now: Long): WorkoutRun {
        if (!s.running || s.phase == WorkoutPhase.DONE || now < s.endAt) return s
        var cur = s
        var guard = 0
        while (cur.phase != WorkoutPhase.DONE && now >= cur.endAt && guard++ < 5_000) {
            val boundary = cur.endAt
            val n = nextSlice(cur)
            if (n.phase == WorkoutPhase.DONE) return n
            val dur = sliceMs(n).coerceAtLeast(1)
            val behind = now - boundary
            if (behind < dur) return n.copy(running = true, endAt = now + dur, leftMs = -1)
            cur = n.copy(running = true, endAt = boundary + dur, leftMs = -1)
        }
        return cur
    }

    fun step(s: WorkoutRun): WorkoutStep? = s.plan.moves.getOrNull(s.move)

    /** 休息时，下一段要做的动作。 */
    fun nextName(s: WorkoutRun): String? {
        val p = s.plan
        if (s.phase != WorkoutPhase.REST || p.moves.isEmpty()) return null
        val idx = when {
            s.move < p.moves.lastIndex -> s.move + 1
            s.round + 1 < p.rounds.coerceAtLeast(1) -> 0
            else -> return null
        }
        return p.moves[idx].name
    }

    /** 环中间的剩余时间。不到 10 秒显示一位小数。 */
    fun remainText(ms: Long): String {
        if (ms >= 60_000) {
            val t = (ms + 999) / 1000
            return "%d:%02d".format(t / 60, t % 60)
        }
        if (ms >= 10_000) return "${(ms + 999) / 1000}"
        val tenths = (ms.coerceAtLeast(0) + 99) / 100
        return "${tenths / 10}.${tenths % 10}"
    }

    private fun afterMove(s: WorkoutRun): WorkoutRun {
        val plan = s.plan
        val lastMove = s.move >= plan.moves.lastIndex
        val lastRound = s.round + 1 >= plan.rounds.coerceAtLeast(1)
        if (lastMove && lastRound) return done(s)
        if (plan.restMs > 0) return s.copy(phase = WorkoutPhase.REST)
        return goNext(s)
    }

    private fun goNext(s: WorkoutRun): WorkoutRun {
        val plan = s.plan
        if (plan.moves.isEmpty()) return done(s)
        return when {
            s.move < plan.moves.lastIndex -> s.copy(move = s.move + 1, rep = 0, phase = WorkoutPhase.REP)
            s.round + 1 < plan.rounds.coerceAtLeast(1) -> s.copy(round = s.round + 1, move = 0, rep = 0, phase = WorkoutPhase.REP)
            else -> done(s)
        }
    }

    private fun done(s: WorkoutRun) = s.copy(phase = WorkoutPhase.DONE, running = false, endAt = 0, leftMs = -1)
}

object WorkoutJson {
    fun groups(list: List<WorkoutGroup>): String = JSONArray().apply { list.forEach { put(group(it)) } }.toString()

    fun groupsFrom(raw: String?): List<WorkoutGroup> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val a = JSONArray(raw)
            (0 until a.length()).mapNotNull { i -> runCatching { groupFrom(a.getJSONObject(i)) }.getOrNull() }
        }.getOrDefault(emptyList())
    }

    fun run(s: WorkoutRun): String = JSONObject()
        .put("groupId", s.plan.groupId).put("title", s.plan.title).put("rounds", s.plan.rounds).put("restMs", s.plan.restMs)
        .put("moves", JSONArray().apply {
            s.plan.moves.forEach { put(JSONObject().put("name", it.name).put("reps", it.reps).put("repMs", it.repMs)) }
        })
        .put("round", s.round).put("move", s.move).put("rep", s.rep).put("phase", s.phase.name)
        .put("running", s.running).put("endAt", s.endAt).put("leftMs", s.leftMs)
        .toString()

    fun runFrom(raw: String?): WorkoutRun? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            val o = JSONObject(raw)
            val moves = o.getJSONArray("moves").let { a ->
                (0 until a.length()).map { i ->
                    val m = a.getJSONObject(i)
                    WorkoutStep(m.optString("name").ifBlank { "动作" }, m.optInt("reps", 1).coerceIn(1, 999), m.optLong("repMs", 1_000).coerceAtLeast(1))
                }
            }
            if (moves.isEmpty()) return null
            val plan = WorkoutPlan(o.optString("groupId"), o.optString("title").ifBlank { "锻炼" },
                o.optInt("rounds", 1).coerceIn(1, 99), o.optLong("restMs", 0).coerceAtLeast(0), moves)
            WorkoutRun(plan, o.optInt("round", 0).coerceAtLeast(0), o.optInt("move", 0).coerceIn(0, moves.lastIndex),
                o.optInt("rep", 0).coerceAtLeast(0),
                runCatching { WorkoutPhase.valueOf(o.optString("phase", "REP")) }.getOrDefault(WorkoutPhase.REP),
                o.optBoolean("running", false), o.optLong("endAt", 0), o.optLong("leftMs", -1))
        }.getOrNull()
    }

    private fun group(g: WorkoutGroup) = JSONObject()
        .put("id", g.id).put("name", g.name).put("rounds", g.rounds).put("rest", g.rest).put("restUnit", g.restUnit.name)
        .put("moves", JSONArray().apply { g.moves.forEach { put(move(it)) } })

    private fun move(m: WorkoutMove) = JSONObject()
        .put("id", m.id).put("name", m.name).put("reps", m.reps).put("each", m.each).put("unit", m.unit.name)

    private fun groupFrom(o: JSONObject): WorkoutGroup? {
        val id = o.optString("id")
        if (id.isEmpty()) return null
        val moves = o.optJSONArray("moves")?.let { a ->
            (0 until a.length()).mapNotNull { i ->
                val m = a.optJSONObject(i) ?: return@mapNotNull null
                val mid = m.optString("id")
                if (mid.isEmpty()) return@mapNotNull null
                WorkoutMove(mid, m.optString("name").take(20), m.optInt("reps", 10).coerceIn(1, 999),
                    m.optInt("each", 2).coerceIn(1, 999), WorkoutUnit.from(m.optString("unit")))
            }
        } ?: emptyList()
        return WorkoutGroup(id, o.optString("name").ifBlank { "运动组" }.take(20), o.optInt("rounds", 1).coerceIn(1, 99),
            o.optInt("rest", 0).coerceIn(0, 999), WorkoutUnit.from(o.optString("restUnit")), moves)
    }
}

class WorkoutApp(private val host: AppHost) : GlassesApp {
    private val prefs = host.context.getSharedPreferences("workout", Context.MODE_PRIVATE)

    override val title = "锻炼"

    var groups: List<WorkoutGroup> = WorkoutJson.groupsFrom(prefs.getString("groups", null))
        private set

    var selectedId: String
        get() = prefs.getString("selected", "") ?: ""
        private set(v) { prefs.edit().putString("selected", v).apply() }

    /** 正在编辑的那一组（只在这次打开里有效）。 */
    var editingId: String? = null

    var run: WorkoutRun? = WorkoutJson.runFrom(prefs.getString("run", null))
        private set

    fun editingGroup(): WorkoutGroup? = groups.find { it.id == editingId }
    fun selectedGroup(): WorkoutGroup? = groups.find { it.id == selectedId } ?: groups.firstOrNull()

    fun addGroup(): WorkoutGroup {
        val g = WorkoutGroup("g${System.currentTimeMillis()}", "运动组", 1, 30, WorkoutUnit.SEC, emptyList())
        putGroups(groups + g)
        selectedId = g.id
        editingId = g.id
        return g
    }

    fun rename(id: String, name: String) = update(id) { it.copy(name = name.trim().ifEmpty { "运动组" }.take(20)) }
    fun setRounds(id: String, n: Int) = update(id) { it.copy(rounds = n.coerceIn(1, 99)) }
    fun setRest(id: String, n: Int) = update(id) { it.copy(rest = n.coerceIn(0, 999)) }
    fun setRestUnit(id: String, unit: WorkoutUnit) = update(id) { it.copy(restUnit = unit) }

    fun deleteGroup(id: String) {
        putGroups(groups.filter { it.id != id })
        if (selectedId == id) selectedId = groups.firstOrNull()?.id ?: ""
        if (editingId == id) editingId = null
        if (run?.plan?.groupId == id) stop()
    }

    fun addMove(groupId: String, move: WorkoutMove) = update(groupId) { it.copy(moves = it.moves + move) }
    fun updateMove(groupId: String, move: WorkoutMove) = update(groupId) { g ->
        g.copy(moves = g.moves.map { if (it.id == move.id) move else it })
    }
    fun deleteMove(groupId: String, moveId: String) = update(groupId) { g -> g.copy(moves = g.moves.filter { it.id != moveId }) }

    fun shiftMove(groupId: String, moveId: String, delta: Int) = update(groupId) { g ->
        val i = g.moves.indexOfFirst { it.id == moveId }
        val j = i + delta
        if (i < 0 || j !in g.moves.indices) g
        else g.copy(moves = g.moves.toMutableList().also { list -> list.add(j, list.removeAt(i)) })
    }

    /** 开始（同一组正在进行或暂停时接着做，不从头来）。没有动作时返回 false。 */
    fun startGroup(g: WorkoutGroup): Boolean {
        if (g.moves.isEmpty()) return false
        selectedId = g.id
        val cur = run
        val same = cur != null && cur.plan.groupId == g.id && cur.phase != WorkoutPhase.DONE
        val now = System.currentTimeMillis()
        val next = if (same) WorkoutLogic.start(cur!!, now) else WorkoutLogic.start(WorkoutLogic.fresh(WorkoutLogic.planOf(g)), now)
        if (!same) host.log("锻炼：开始「${g.name}」")
        commit(next)
        return true
    }

    /** 还没开始时，先把这一组摆上（不计时），方便眼镜上按右键开始。 */
    fun arm(g: WorkoutGroup) {
        if (g.moves.isEmpty()) return
        val cur = run
        if (cur != null && cur.phase != WorkoutPhase.DONE) return
        selectedId = g.id
        putRun(WorkoutLogic.fresh(WorkoutLogic.planOf(g)))
        show()
    }

    fun restart() {
        val plan = run?.plan ?: selectedGroup()?.let { WorkoutLogic.planOf(it) } ?: return
        if (plan.moves.isEmpty()) return
        host.log("锻炼：重新开始「${plan.title}」")
        commit(WorkoutLogic.start(WorkoutLogic.fresh(plan), System.currentTimeMillis()))
    }

    fun startPause() {
        val s = run ?: return
        if (s.phase == WorkoutPhase.DONE) { restart(); return }
        val now = System.currentTimeMillis()
        commit(if (s.running) WorkoutLogic.pause(s, now) else WorkoutLogic.start(s, now))
    }

    fun skipRep() {
        val s = run ?: return
        if (s.phase == WorkoutPhase.DONE) return
        val n = WorkoutLogic.nextSlice(s)
        val now = System.currentTimeMillis()
        commit(if (s.running && n.phase != WorkoutPhase.DONE) WorkoutLogic.start(n, now) else n)
    }

    fun skipMove() {
        val s = run ?: return
        if (s.phase == WorkoutPhase.DONE) return
        val n = WorkoutLogic.skipMove(s)
        val now = System.currentTimeMillis()
        commit(if (s.running && n.phase != WorkoutPhase.DONE) WorkoutLogic.start(n, now) else n)
    }

    fun stop() {
        putRun(null)
        host.main.removeCallbacks(due)
        wake?.let { if (it.isHeld) it.release() }; wake = null
        show()
    }

    fun restore() {
        val s = run ?: return
        if (!s.running) return
        commit(WorkoutLogic.catchUp(s, System.currentTimeMillis()))
    }

    fun remaining(): Long = run?.let { WorkoutLogic.remaining(it, System.currentTimeMillis()) } ?: 0L

    private fun update(id: String, f: (WorkoutGroup) -> WorkoutGroup) {
        val i = groups.indexOfFirst { it.id == id }
        if (i < 0) return
        val n = f(groups[i])
        if (n == groups[i]) return
        putGroups(groups.toMutableList().also { it[i] = n })
    }

    private fun putGroups(v: List<WorkoutGroup>) {
        groups = v
        prefs.edit().putString("groups", WorkoutJson.groups(v)).apply()
        host.changed()
    }

    private fun putRun(v: WorkoutRun?) {
        run = v
        val e = prefs.edit()
        if (v == null) e.remove("run") else e.putString("run", WorkoutJson.run(v))
        e.apply()
    }

    private fun commit(next: WorkoutRun) {
        val finished = run?.phase != WorkoutPhase.DONE && next.phase == WorkoutPhase.DONE
        putRun(next)
        if (finished) {
            host.popup("锻炼完成", next.plan.title.ifBlank { "锻炼" })
            host.log("锻炼完成：${next.plan.title}")
        }
        schedule(); show()
    }

    // ---------- 计时 ----------

    private var wake: PowerManager.WakeLock? = null

    private fun schedule() {
        host.main.removeCallbacks(due)
        wake?.let { if (it.isHeld) it.release() }; wake = null
        val s = run ?: return
        if (!s.running || s.phase == WorkoutPhase.DONE) return
        val left = (s.endAt - System.currentTimeMillis()).coerceAtLeast(0)
        host.main.postDelayed(due, left)
        runCatching {
            val pm = host.context.getSystemService(Context.POWER_SERVICE) as PowerManager
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "yingdu:workout").apply { acquire(left + 60_000) }
        }
    }

    private val due = Runnable {
        val s = run ?: return@Runnable
        if (!s.running) return@Runnable
        val now = System.currentTimeMillis()
        if (now < s.endAt - 50) { schedule(); return@Runnable }
        commit(WorkoutLogic.catchUp(s, now))
    }

    // ---------- 眼镜画面 ----------

    private var open = false

    private fun show() {
        if (open) { host.main.removeCallbacks(loop); host.main.post(loop) }
        host.changed()
    }

    /** 短动作刷新快一些，环才看得出在转；眼镜发图慢时跟着放慢，不把画面堆在一起。 */
    private val loop = object : Runnable {
        override fun run() {
            if (!open) return
            host.redraw(this@WorkoutApp)
            val s = run
            if (s == null || !s.running) return
            val cost = host.frameIntervalMs() ?: 0
            val slice = WorkoutLogic.sliceMs(s)
            val want = if (slice <= 5_000) 200L else if (slice <= 60_000) 500L else 1_000L
            host.main.postDelayed(this, maxOf(want, cost + 50))
        }
    }

    override fun onOpen() { open = true; host.main.removeCallbacks(loop); host.main.post(loop) }
    override fun onClose() { open = false; host.main.removeCallbacks(loop) }
    override fun onResume() { if (open) { host.main.removeCallbacks(loop); host.main.post(loop) } }

    override fun onInput(input: GlassesInput) {
        when (input) {
            GlassesInput.CLICK_RIGHT -> { host.log("锻炼：右单击"); startPause() }
            GlassesInput.CLICK_LEFT -> { host.log("锻炼：左单击"); skipRep() }
            GlassesInput.DOUBLE_RIGHT -> { host.log("锻炼：右双击"); skipMove() }
            else -> {}
        }
    }

    override fun status(): String {
        val s = run ?: return "$title · 还没开始"
        val name = when (s.phase) {
            WorkoutPhase.DONE -> "${s.plan.title} 完成"
            WorkoutPhase.REST -> "休息"
            WorkoutPhase.REP -> WorkoutLogic.step(s)?.name ?: s.plan.title
        }
        val count = WorkoutLogic.step(s)?.let { " ${s.rep}/${it.reps}" } ?: ""
        val clock = if (s.phase == WorkoutPhase.DONE) "" else " " + WorkoutLogic.remainText(remaining())
        val paused = if (!s.running && s.leftMs >= 0 && s.phase != WorkoutPhase.DONE) " 暂停" else ""
        return "$title · $name$count$clock$paused"
    }

    /**
     * 提词器文字版：左边是字符拼成的进度环（TextRing，外圈 ◎ 绕着转），中间是剩余时间，右边是动作和次数。
     * 提词器里眼镜会把单击、长按报给手机，所以锻炼的按键只能在这一页上用。
     */
    override fun glassesText(cols: Int, rows: Int): Pair<String, String>? {
        val now = System.currentTimeMillis()
        val s = run
        if (s == null || s.plan.moves.isEmpty()) {
            return TextRing.lines(0.0, "", listOf("还没有开始", "在手机上点「开始」"), false, now, cols)
                .joinToString("\n") to title
        }
        val step = WorkoutLogic.step(s)
        val left = WorkoutLogic.remaining(s, now)
        val prog = WorkoutLogic.progress(s, now)
        val done = s.phase == WorkoutPhase.DONE
        val center = if (done) "完成" else WorkoutLogic.remainText(left)
        val headline = when (s.phase) {
            WorkoutPhase.REST -> "休息"
            WorkoutPhase.DONE -> "完成"
            else -> step?.name ?: ""
        }
        val count = when (s.phase) {
            WorkoutPhase.REP -> step?.let { "已完成 ${s.rep} / ${it.reps}" } ?: ""
            WorkoutPhase.DONE -> s.plan.title
            WorkoutPhase.REST -> ""
        }
        val state = when {
            done -> "这一组做完了"
            s.running -> "进行中"
            s.leftMs >= 0 -> "已暂停"
            else -> "等你开始"
        }
        val next = WorkoutLogic.nextName(s)?.let { "下一个 $it" } ?: ""
        val hint = if (done) "右键 再来一次" else "右键 开始/暂停 · 左键 这次完成"
        val body = TextRing.lines(prog, center, listOf(headline, count, state, next, hint), s.running, now, cols)
        val status = if (done) "$title · 完成" else "$title · 第 ${s.round + 1}/${s.plan.rounds.coerceAtLeast(1)} 轮"
        return body.joinToString("\n") to status
    }

    override fun render(fontPx: Int): ByteArray {
        val f = Frame(fontPx)
        val s = run
        if (s == null || s.plan.moves.isEmpty()) {
            f.header("锻炼")
            f.text("先在手机上建好运动组", 16, 70, Frame.DIM, f.font)
            return f.done()
        }
        val now = System.currentTimeMillis()
        val step = WorkoutLogic.step(s)
        val left = WorkoutLogic.remaining(s, now)
        val prog = WorkoutLogic.progress(s, now)
        val headRight = when (s.phase) {
            WorkoutPhase.DONE -> "完成"
            WorkoutPhase.REST -> "休息"
            else -> "第 ${s.round + 1}/${s.plan.rounds.coerceAtLeast(1)} 轮"
        }
        f.header("锻炼 · ${s.plan.title}", headRight)

        val cx = 96; val cy = 96; val r = 58; val cell = 5; val n = 60
        val lit = Math.ceil((1 - prog) * n - 1e-9).toInt().coerceIn(0, n)
        PixelClock.ring(n, cx, cy, r).forEachIndexed { i, (x, y) ->
            val level = if (i >= n - lit) PixelClock.FULL else PixelClock.FAINT
            f.fill(x - cell / 2, y - cell / 2, cell, cell, level)
        }
        val clockPx = GlassesFonts.text(22)
        val center = if (s.phase == WorkoutPhase.DONE) "完成" else WorkoutLogic.remainText(left)
        f.text(center, cx, cy - clockPx.height / 2, Frame.FULL, clockPx, Frame.CENTER)

        val rx = 196
        val big = GlassesFonts.text(maxOf(24, fontPx).coerceAtMost(28))
        val headline = when (s.phase) {
            WorkoutPhase.REST -> "休息"
            WorkoutPhase.DONE -> "完成"
            else -> step?.name ?: ""
        }
        f.text(big.ellipsize(headline, f.w - rx - 8), rx, 38, Frame.FULL, big)
        val count = when (s.phase) {
            WorkoutPhase.REP -> step?.let { "已完成 ${s.rep} / ${it.reps}" } ?: ""
            WorkoutPhase.DONE -> s.plan.title
            else -> WorkoutLogic.nextName(s)?.let { "下一个 $it" } ?: "最后一段"
        }
        f.text(f.font.ellipsize(count, f.w - rx - 8), rx, 78, Frame.DIM, f.font)
        val st = when {
            s.phase == WorkoutPhase.DONE -> "这一组做完了"
            s.running -> "进行中"
            s.leftMs >= 0 -> "已暂停"
            else -> "等你开始"
        }
        f.text(st, rx, 112, Frame.DIM, f.small)
        val hint = when (s.phase) {
            WorkoutPhase.DONE -> "右键 再来一次"
            else -> "右键 开始/暂停 · 左键 这次完成"
        }
        f.text(f.small.ellipsize(hint, f.w - rx - 8), rx, f.h - 22, Frame.FAINT, f.small)
        return f.done()
    }
}
