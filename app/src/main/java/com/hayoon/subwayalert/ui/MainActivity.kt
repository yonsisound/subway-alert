package com.hayoon.subwayalert.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hayoon.subwayalert.data.DataRepository
import com.hayoon.subwayalert.ride.RideService
import com.hayoon.subwayalert.ride.RideState
import com.hayoon.subwayalert.ride.Stop
import com.hayoon.subwayalert.route.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed class Screen {
    data object Home : Screen()
    data class Results(val from: Int, val to: Int, val mode: Mode, val routes: List<Route>) : Screen()
    data class Detail(val parent: Results, val route: Route) : Screen()
    data object Ride : Screen()
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        RideService.createChannels(this)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF2E7D5B), secondary = Color(0xFF00897B))) {
                Surface(Modifier.fillMaxSize()) { App() }
            }
        }
    }
}

private fun fmtMin(sec: Int) = "${(sec + 59) / 60}분"

@Composable
private fun App() {
    val ctx = LocalContext.current
    var version by remember { mutableIntStateOf(0) }  // 데이터 갱신 시 재로딩용
    val net = remember(version) { DataRepository.network(ctx) }
    val router = remember(net) { Router(net) }
    val ride by RideState.flow.collectAsStateWithLifecycle()
    var screen by remember { mutableStateOf<Screen>(if (RideService.isRiding(ctx)) Screen.Ride else Screen.Home) }

    LaunchedEffect(Unit) { if (RideService.isRiding(ctx) && RideState.ui.value == null) RideService.send(ctx, "restore") }

    BackHandler(enabled = screen !is Screen.Home && screen !is Screen.Ride) {
        screen = when (val s = screen) {
            is Screen.Detail -> s.parent
            else -> Screen.Home
        }
    }

    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        when (val s = screen) {
            Screen.Home -> HomeScreen(net, router, onUpdated = { version++ },
                onRide = { screen = Screen.Ride }) { r -> screen = r }
            is Screen.Results -> ResultsScreen(net, s, onBack = { screen = Screen.Home }) { screen = Screen.Detail(s, it) }
            is Screen.Detail -> DetailScreen(net, router, s, onBack = { screen = s.parent },
                onRoute = { screen = s.copy(route = it) }) { screen = Screen.Ride }
            Screen.Ride -> RideScreen(net, ride, onDone = { screen = Screen.Home })
        }
    }
}

// ---------------------------------------------------------------- 공통

@Composable
private fun LineChip(net: Network, line: String, small: Boolean = false) {
    val c = Color(net.lines[line]?.color ?: 0xFF888888)
    Box(Modifier.background(c, RoundedCornerShape(50)).padding(horizontal = if (small) 6.dp else 10.dp, vertical = 2.dp)) {
        Text(Network.shortName(line), color = Color.White, fontSize = if (small) 11.sp else 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
private fun TopBar(title: String, onBack: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) TextButton(onClick = onBack) { Text("〈 뒤로") }
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f).padding(start = 8.dp))
        actions()
    }
}

// ---------------------------------------------------------------- 홈

@Composable
private fun HomeScreen(net: Network, router: Router, onUpdated: () -> Unit, onRide: () -> Unit, onResult: (Screen.Results) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var from by remember { mutableStateOf<Station?>(null) }
    var to by remember { mutableStateOf<Station?>(null) }
    var mode by remember { mutableStateOf(Mode.MIN_TIME) }
    var msg by remember { mutableStateOf<String?>(null) }
    var updating by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    val recent = remember { mutableStateOf(DataRepository.recent(ctx)) }

    fun search(f: Station, t: Station) {
        if (f.id == t.id) { msg = "출발역과 도착역이 같아요."; return }
        searching = true
        scope.launch {
            val routes = withContext(Dispatchers.Default) { router.alternatives(f.id, t.id, mode) }
            searching = false
            if (routes.isEmpty()) msg = "경로를 찾지 못했어요."
            else { DataRepository.addRecent(ctx, f.id, t.id); onResult(Screen.Results(f.id, t.id, mode, routes)) }
        }
    }

    TopBar("지하철 알림") {
        TextButton(enabled = !updating, onClick = {
            updating = true
            scope.launch {
                msg = when (val r = DataRepository.update(ctx)) {
                    is DataRepository.UpdateResult.Updated -> { onUpdated(); "노선 업데이트 완료 (버전 ${r.version}, 역 ${r.stations}개)" }
                    is DataRepository.UpdateResult.UpToDate -> "이미 최신 노선이에요 (버전 ${r.version})"
                    is DataRepository.UpdateResult.Failed -> "업데이트 실패: ${r.reason}"
                }
                updating = false
            }
        }) { Text(if (updating) "확인 중…" else "노선 업데이트") }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        if (RideService.isRiding(ctx)) {
            Card(Modifier.fillMaxWidth().padding(bottom = 12.dp).clickable { onRide() },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Text("탑승 안내 진행 중 — 눌러서 보기", Modifier.padding(16.dp), fontWeight = FontWeight.Bold)
            }
        }
        StationField("출발역", from, net) { from = it }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { val t = from; from = to; to = t }) { Text("⇅ 바꾸기") }
        }
        StationField("도착역", to, net) { to = it }
        Spacer(Modifier.height(12.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(selected = mode == Mode.MIN_TIME, onClick = { mode = Mode.MIN_TIME },
                shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text("최소시간") }
            SegmentedButton(selected = mode == Mode.MIN_TRANSFER, onClick = { mode = Mode.MIN_TRANSFER },
                shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text("최소환승") }
        }
        Spacer(Modifier.height(12.dp))
        Button(onClick = { val f = from; val t = to; if (f != null && t != null) search(f, t) else msg = "출발역과 도착역을 골라 주세요." },
            enabled = !searching, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text(if (searching) "찾는 중…" else "경로 검색", fontSize = 17.sp)
        }
        msg?.let { Text(it, Modifier.padding(top = 10.dp), color = MaterialTheme.colorScheme.secondary) }

        if (recent.value.isNotEmpty()) {
            Text("최근 검색", Modifier.padding(top = 24.dp, bottom = 6.dp), fontWeight = FontWeight.Bold)
            recent.value.forEach { (a, b) ->
                val sa = net.stations[a]; val sb = net.stations[b]
                if (sa != null && sb != null) {
                    Text("${sa.name} → ${sb.name}", Modifier.fillMaxWidth().clickable { from = sa; to = sb; search(sa, sb) }.padding(vertical = 10.dp))
                    HorizontalDivider()
                }
            }
        }
        Spacer(Modifier.weight(1f))
        BatteryHint()
        Text("노선 데이터 ${net.generated} (v${net.version}) · OpenStreetMap·서울 열린데이터광장",
            fontSize = 11.sp, color = Color.Gray, modifier = Modifier.padding(vertical = 8.dp))
    }
}

@SuppressLint("BatteryLife")
@Composable
private fun BatteryHint() {
    val ctx = LocalContext.current
    val pm = ctx.getSystemService(PowerManager::class.java)
    var ignoring by remember { mutableStateOf(pm.isIgnoringBatteryOptimizations(ctx.packageName)) }
    if (!ignoring) {
        OutlinedButton(onClick = {
            ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))
            ignoring = true
        }, modifier = Modifier.fillMaxWidth()) { Text("배터리 최적화 예외 설정 (알림 안정성)") }
    }
}

@Composable
private fun StationField(label: String, value: Station?, net: Network, onPick: (Station) -> Unit) {
    var text by remember(value) { mutableStateOf(value?.name ?: "") }
    var open by remember { mutableStateOf(false) }
    val results = remember(text) { if (open) net.search(text) else emptyList() }
    Column {
        OutlinedTextField(
            value = text, onValueChange = { text = it; open = true },
            label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        if (open && results.isNotEmpty()) {
            Card(Modifier.fillMaxWidth().heightIn(max = 260.dp)) {
                LazyColumn {
                    items(results) { s ->
                        Row(Modifier.fillMaxWidth().clickable { onPick(s); text = s.name; open = false }.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(s.name, Modifier.weight(1f), fontWeight = FontWeight.Medium)
                            net.linesAt[s.id].orEmpty().sorted().take(4).forEach { LineChip(net, it, small = true); Spacer(Modifier.width(3.dp)) }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 결과

@Composable
private fun RouteSummary(net: Network, r: Route) {
    Column(Modifier.padding(14.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(fmtMin(r.totalSec), fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text("  환승 ${r.transfers}회 · ${r.legs.sumOf { it.stops }}정거장", color = Color.Gray)
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            r.legs.forEachIndexed { i, l ->
                if (i > 0) Text(" › ", color = Color.Gray)
                LineChip(net, l.line, small = true)
            }
        }
        if (r.transfers > 0) Text("환승: " + r.transferStations.joinToString(", ") { net.stations[it]?.name ?: "" },
            fontSize = 13.sp, color = Color.Gray, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun ResultsScreen(net: Network, s: Screen.Results, onBack: () -> Unit, onPick: (Route) -> Unit) {
    TopBar("${net.stations[s.from]?.name} → ${net.stations[s.to]?.name}", onBack)
    Text(if (s.mode == Mode.MIN_TIME) "최소시간 순" else "최소환승 순", Modifier.padding(horizontal = 16.dp), color = Color.Gray)
    LazyColumn(Modifier.padding(horizontal = 12.dp)) {
        items(s.routes) { r ->
            Card(Modifier.fillMaxWidth().padding(vertical = 6.dp).clickable { onPick(r) }) { RouteSummary(net, r) }
        }
        item { Text("※ 예상 시간은 시간표·거리 기반 추정이에요. 환승은 평균 ${net.transferSec / 60}분(도보+대기)으로 계산해요.",
            fontSize = 12.sp, color = Color.Gray, modifier = Modifier.padding(8.dp)) }
    }
}

// ---------------------------------------------------------------- 상세

@Composable
private fun DetailScreen(net: Network, router: Router, s: Screen.Detail, onBack: () -> Unit, onRoute: (Route) -> Unit, onStart: () -> Unit) {
    val ctx = LocalContext.current
    val r = s.route
    var pickIdx by remember { mutableStateOf<Int?>(null) }
    var showStops by remember { mutableStateOf(setOf<Int>()) }

    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        RideService.start(ctx, r); onStart()
    }

    Column(Modifier.fillMaxSize()) {
    TopBar("경로 상세", onBack)
    LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp)) {
        item { Card(Modifier.fillMaxWidth()) { RouteSummary(net, r) }; Spacer(Modifier.height(12.dp)) }
        itemsIndexed(r.legs) { i, leg ->
            val c = Color(net.lines[leg.line]?.color ?: 0xFF888888)
            val next = net.stations[leg.stations[1]]?.name
            val term = net.terminalToward(leg.stations[0], leg.stations[1], leg.line)
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Box(Modifier.width(6.dp).heightIn(min = 70.dp).background(c, RoundedCornerShape(3.dp)))
                Column(Modifier.padding(start = 12.dp).weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LineChip(net, leg.line)
                        Text("  ${net.stations[leg.from]?.name} 승차", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                    Text((if (term != null) "$term 방면 · " else "") + "다음역 $next", fontSize = 13.sp, color = Color.Gray)
                    Text("${leg.stops}정거장 · ${fmtMin(leg.rideSec)}", fontSize = 13.sp,
                        modifier = Modifier.clickable { showStops = if (i in showStops) showStops - i else showStops + i })
                    if (i in showStops) leg.stations.drop(1).dropLast(1).forEach {
                        Text("· ${net.stations[it]?.name}", fontSize = 13.sp, color = Color.Gray)
                    }
                    Text(if (i == r.legs.size - 1) "${net.stations[leg.to]?.name} 하차" else "${net.stations[leg.to]?.name} 하차",
                        fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.padding(top = 2.dp))
                }
            }
            if (i < r.legs.size - 1) {
                OutlinedButton(onClick = { pickIdx = i }, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Text("🔁 ${net.stations[leg.to]?.name} 환승 (${fmtMin(r.transferSecs[i])}) — 환승역 변경")
                }
            }
        }
    }
    Button(onClick = {
        val ps = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) ps.add(Manifest.permission.POST_NOTIFICATIONS)
        perm.launch(ps.toTypedArray())
    }, modifier = Modifier.fillMaxWidth().padding(16.dp).height(56.dp)) { Text("탑승 시작 (지금 출발)", fontSize = 18.sp) }
    }

    pickIdx?.let { idx ->
        var options by remember(idx, r) { mutableStateOf<List<TransferOption>?>(null) }
        LaunchedEffect(idx, r) { options = withContext(Dispatchers.Default) { router.transferOptions(r, idx, s.parent.mode) } }
        AlertDialog(
            onDismissRequest = { pickIdx = null },
            confirmButton = { TextButton(onClick = { pickIdx = null }) { Text("닫기") } },
            title = { Text("${Network.shortName(r.legs[idx].line)} → ${Network.shortName(r.legs[idx + 1].line)} 환승역") },
            text = {
                val list = options
                when {
                    list == null -> Text("계산 중…")
                    list.isEmpty() -> Text("두 노선이 만나는 다른 환승역이 없어요.")
                    else -> LazyColumn(Modifier.heightIn(max = 400.dp)) {
                        items(list) { o ->
                            val diff = o.route.totalSec - r.totalSec
                            Row(Modifier.fillMaxWidth().clickable { onRoute(o.route); pickIdx = null }.padding(vertical = 12.dp)) {
                                Text(net.stations[o.station]?.name ?: "", Modifier.weight(1f), fontWeight = FontWeight.Bold)
                                Text("${fmtMin(o.route.totalSec)} (${if (diff >= 0) "+" else "-"}${fmtMin(kotlin.math.abs(diff))}) · 환승 ${o.route.transfers}",
                                    fontSize = 13.sp, color = if (diff <= 0) MaterialTheme.colorScheme.primary else Color.Gray)
                            }
                            HorizontalDivider()
                        }
                    }
                }
            },
        )
    }
}

// ---------------------------------------------------------------- 탑승 중

@Composable
private fun RideScreen(net: Network, ride: com.hayoon.subwayalert.ride.RideUi?, onDone: () -> Unit) {
    val ctx = LocalContext.current
    var picking by remember { mutableStateOf(false) }
    TopBar("탑승 안내")
    if (ride == null) {
        Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("진행 중인 안내가 없어요.")
            Spacer(Modifier.height(12.dp))
            Button(onClick = onDone) { Text("처음으로") }
        }
        return
    }
    val t = ride.tracker; val st = ride.status
    val cur = t.stops[st.current]
    val target = st.nextTarget?.let { t.stops[it] }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Column(Modifier.padding(16.dp)) {
                Text(when {
                    st.finished -> "도착했어요"
                    st.waitingTransfer -> "환승 중"
                    st.atStation -> "지금 정차 중 (추정)"
                    else -> "방금 지난 역 (추정)"
                }, color = Color.Gray)
                Text(net.stations[cur.station]?.name ?: "", fontSize = 30.sp, fontWeight = FontWeight.Bold)
                if (target != null && !st.finished) {
                    val what = if (target.kind == Stop.Kind.DEST) "하차" else "환승"
                    Text("${net.stations[target.station]?.name} ${what}까지 ${st.stopsToTarget}정거장 · 약 ${fmtMin(st.secToTarget)}",
                        fontSize = 16.sp, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (st.waitingTransfer)
                Button(onClick = { RideService.send(ctx, RideService.ACTION_BOARDED) }, Modifier.weight(1f)) { Text("환승 열차 탔어요") }
            else
                Button(onClick = { RideService.send(ctx, RideService.ACTION_NEXT) }, Modifier.weight(1f), enabled = !st.finished) { Text("다음 역 도착") }
            OutlinedButton(onClick = { picking = !picking }, Modifier.weight(1f)) { Text(if (picking) "수정 취소" else "현재 역 수정") }
        }
        OutlinedButton(onClick = { RideService.send(ctx, RideService.ACTION_STOP); onDone() }, Modifier.fillMaxWidth()) { Text("안내 종료") }
        if (picking) Text("아래 목록에서 지금 있는 역을 눌러 주세요.", fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(vertical = 8.dp))
        else Text("위치 신호가 잡히면 자동 보정돼요. 지하에서 틀리면 '현재 역 수정'으로 맞춰 주세요.",
            fontSize = 12.sp, color = Color.Gray, modifier = Modifier.padding(vertical = 8.dp))
    }
    val listState = rememberLazyListState()
    LaunchedEffect(st.current) { listState.animateScrollToItem(maxOf(0, st.current - 2)) }
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        itemsIndexed(t.stops) { k, s ->
            val line = t.route.legs[s.legIndex].line
            val c = Color(net.lines[line]?.color ?: 0xFF888888)
            val passed = k < st.current
            Row(Modifier.fillMaxWidth().height(44.dp).clickable(enabled = picking) {
                RideService.send(ctx, RideService.ACTION_SET_STOP, k); picking = false
            }, verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(if (k == st.current) 16.dp else 10.dp).background(if (passed) Color.LightGray else c, CircleShape))
                Spacer(Modifier.width(12.dp))
                Text(net.stations[s.station]?.name ?: "", Modifier.weight(1f),
                    fontWeight = if (k == st.current || s.kind != Stop.Kind.PASS) FontWeight.Bold else FontWeight.Normal,
                    color = if (passed) Color.Gray else Color.Unspecified, maxLines = 1, overflow = TextOverflow.Ellipsis)
                when (s.kind) {
                    Stop.Kind.TRANSFER -> { Text("환승 → ", fontSize = 12.sp); LineChip(net, t.route.legs[s.legIndex + 1].line, true) }
                    Stop.Kind.DEST -> Text("하차", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Stop.Kind.ORIGIN -> LineChip(net, line, true)
                    else -> {}
                }
            }
        }
    }
}
