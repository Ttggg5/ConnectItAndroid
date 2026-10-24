package com.connectit.android.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.connectit.android.net.RemoteKeys
import com.connectit.android.screen.RemoteControlState
import com.connectit.android.service.ConnectItService
import com.connectit.android.ui.theme.BrandColors
import kotlinx.coroutines.delay

// 遙控器機身的配色:深色霧面塑膠 + 品牌色的 OK 鍵,不跟著淺色/深色主題變(實體遙控器本來就是固定的顏色)。
private val BodyTop = Color(0xFF2B3142)
private val BodyBottom = Color(0xFF151923)
private val BodyEdge = Color(0x1FFFFFFF)
private val KeyFace = Color(0xFF262C3B)
private val KeyFacePressed = Color(0xFF1B202B)
private val KeyEdge = Color(0x14FFFFFF)
private val KeyText = Color(0xFFE4E8F4)
private val KeyTextMuted = Color(0xFF8A93AD)
private val DPadRing = Color(0xFF1E2330)

/**
 * 遙控器畫面(遙控中整個畫面都讓給它,跟觀看螢幕鏡像一樣),外觀做成一支實體遙控器:上方電源鍵(結束遙控)、
 * 指示燈(每按一個鍵閃一下)、靜音;中間方向盤 + OK;下面音量/翻頁的搖桿鍵與播放鍵,最下面是彩色功能鍵。
 * 按鍵按下時會縮小、有震動回饋。
 *
 * 對方是電腦:方向盤 = 方向鍵、OK = Enter,翻頁搖桿 = PageUp/PageDown(簡報),另有返回(Esc)/開始(Win)/切換視窗與功能鍵。
 * 對方是 Android(只能送媒體鍵與音量):方向盤上下 = 音量、左右 = 上一首/下一首、OK = 播放/暫停。
 * 實體音量鍵也會轉送給對方(見 MainActivity)。返回鍵 = 結束遙控。
 */
@Composable
fun RemoteControlScreen(service: ConnectItService, state: RemoteControlState) {
    val controller = service.remoteControl
    BackHandler { controller.stop() }
    val isWindows = state.platform != "android"
    val haptics = LocalHapticFeedback.current
    var presses by remember { mutableIntStateOf(0) }

    fun press(vararg keys: String) {
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        presses++
        controller.sendShortcut(keys.toList())
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF0E1322), Color(0xFF05070D)))),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(vertical = 16.dp, horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            RemoteBody {
                // ---- 頂部:電源 / 指示燈 / 靜音 ----
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    RoundKey(
                        icon = Icons.Filled.PowerSettingsNew,
                        description = "結束遙控",
                        size = 52.dp,
                        face = Color(0xFFB4232F),
                        facePressed = Color(0xFF8E1B25),
                        onClick = { controller.stop() },
                    )
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        StatusLed(presses)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            state.peer?.name ?: "對方",
                            color = KeyTextMuted,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    RoundKey(Icons.AutoMirrored.Filled.VolumeOff, "靜音", size = 52.dp) { press(RemoteKeys.VOLUME_MUTE) }
                }

                Spacer(Modifier.height(28.dp))

                // ---- 方向盤 ----
                if (isWindows) {
                    DPad(
                        onUp = { press("ArrowUp") },
                        onDown = { press("ArrowDown") },
                        onLeft = { press("ArrowLeft") },
                        onRight = { press("ArrowRight") },
                        onOk = { press("Enter") },
                        okLabel = "OK",
                    )
                } else {
                    DPad(
                        onUp = { press(RemoteKeys.VOLUME_UP) },
                        onDown = { press(RemoteKeys.VOLUME_DOWN) },
                        onLeft = { press(RemoteKeys.PREVIOUS) },
                        onRight = { press(RemoteKeys.NEXT) },
                        onOk = { press(RemoteKeys.PLAY_PAUSE) },
                        okIcon = Icons.Filled.PlayArrow,
                        hints = DPadHints(up = "音量+", down = "音量−", left = "上一首", right = "下一首"),
                    )
                }

                Spacer(Modifier.height(24.dp))

                // ---- 方向盤下方的三顆圓鍵 ----
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    if (isWindows) {
                        LabeledRoundKey("返回", "Esc") { press("Escape") }
                        LabeledRoundKey("開始", "Win") { press("Meta") }
                        LabeledRoundKey("切換", "Alt+Tab") { press("Alt", "Tab") }
                    } else {
                        LabeledRoundKey("上一首", icon = Icons.Filled.SkipPrevious) { press(RemoteKeys.PREVIOUS) }
                        LabeledRoundKey("停止", icon = Icons.Filled.Stop) { press(RemoteKeys.STOP) }
                        LabeledRoundKey("下一首", icon = Icons.Filled.SkipNext) { press(RemoteKeys.NEXT) }
                    }
                }

                Spacer(Modifier.height(26.dp))

                // ---- 搖桿鍵 + 播放 ----
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Rocker(label = "VOL", onUp = { press(RemoteKeys.VOLUME_UP) }, onDown = { press(RemoteKeys.VOLUME_DOWN) })
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        RoundKey(
                            icon = Icons.Filled.PlayArrow,
                            description = "播放/暫停",
                            size = 76.dp,
                            face = BrandColors.IndigoDeep,
                            facePressed = Color(0xFF3B33B8),
                            onClick = { press(RemoteKeys.PLAY_PAUSE) },
                        )
                        // 對方是 Android 時上一首/停止/下一首已經在方向盤下方那排,這裡就不重複了。
                        if (isWindows) {
                            Spacer(Modifier.height(12.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                RoundKey(Icons.Filled.SkipPrevious, "上一首", size = 40.dp) { press(RemoteKeys.PREVIOUS) }
                                RoundKey(Icons.Filled.Stop, "停止", size = 40.dp) { press(RemoteKeys.STOP) }
                                RoundKey(Icons.Filled.SkipNext, "下一首", size = 40.dp) { press(RemoteKeys.NEXT) }
                            }
                        }
                    }
                    if (isWindows) {
                        Rocker(label = "PG", onUp = { press("PageUp") }, onDown = { press("PageDown") }, upIcon = Icons.Filled.KeyboardArrowUp, downIcon = Icons.Filled.KeyboardArrowDown)
                    } else {
                        Rocker(label = "曲", onUp = { press(RemoteKeys.NEXT) }, onDown = { press(RemoteKeys.PREVIOUS) }, upIcon = Icons.Filled.KeyboardArrowUp, downIcon = Icons.Filled.KeyboardArrowDown)
                    }
                }

                // ---- 功能鍵(只有電腦能用):像電視遙控器的彩色鍵 + 一排小鍵 ----
                if (isWindows) {
                    Spacer(Modifier.height(28.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ColorKey("投影片", "F5", Color(0xFFE5484D), Modifier.weight(1f)) { press("F5") }
                        ColorKey("黑畫面", "B", Color(0xFF30A46C), Modifier.weight(1f)) { press("b") }
                        ColorKey("桌面", "Win+D", Color(0xFFF5D90A), Modifier.weight(1f)) { press("Meta", "d") }
                        ColorKey("關閉", "Alt+F4", Color(0xFF3E63DD), Modifier.weight(1f)) { press("Alt", "F4") }
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PillKey("複製", Modifier.weight(1f)) { press("Control", "c") }
                        PillKey("貼上", Modifier.weight(1f)) { press("Control", "v") }
                        PillKey("復原", Modifier.weight(1f)) { press("Control", "z") }
                    }
                }

                Spacer(Modifier.height(30.dp))
                // 機身下緣的「品牌壓印」。
                Text(
                    "ConnectIt",
                    color = Color(0x40FFFFFF),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 3.sp,
                )
            }

            Spacer(Modifier.height(14.dp))
            Text(
                if (isWindows) "實體音量鍵也會調整對方的音量。按返回鍵結束遙控。"
                else "Android 只支援媒體鍵與音量。實體音量鍵也會調整對方的音量。",
                color = KeyTextMuted,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** 遙控器機身:圓角長條、上亮下暗的霧面漸層、細細的邊框與投影。 */
@Composable
private fun RemoteBody(content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(topStart = 64.dp, topEnd = 64.dp, bottomStart = 48.dp, bottomEnd = 48.dp)
    Column(
        Modifier
            .widthIn(max = 340.dp)
            .fillMaxWidth()
            .shadow(24.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)
            .clip(shape)
            .background(Brush.verticalGradient(listOf(BodyTop, BodyBottom)))
            .border(BorderStroke(1.dp, BodyEdge), shape)
            .padding(horizontal = 26.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        content()
    }
}

/** 指示燈:平常是暗的,每按一個鍵亮一下(就像實體遙控器送出紅外線時的那顆燈)。 */
@Composable
private fun StatusLed(presses: Int) {
    var lit by remember { mutableStateOf(false) }
    LaunchedEffect(presses) {
        if (presses == 0) return@LaunchedEffect
        lit = true
        delay(140)
        lit = false
    }
    val color by animateColorAsState(if (lit) BrandColors.Cyan else Color(0xFF3A4152), tween(if (lit) 40 else 260), label = "led")
    Box(
        Modifier
            .size(10.dp)
            .shadow(if (lit) 8.dp else 0.dp, CircleShape, ambientColor = BrandColors.Cyan, spotColor = BrandColors.Cyan)
            .clip(CircleShape)
            .background(color),
    )
}

/** 按下去會縮一點、顏色變深的鍵(所有遙控器按鍵的共同外觀)。 */
@Composable
private fun PressableKey(
    shape: Shape,
    modifier: Modifier = Modifier,
    face: Color = KeyFace,
    facePressed: Color = KeyFacePressed,
    description: String? = null,
    /** 平面鍵(方向盤的方向、搖桿鍵的上下半):本身沒有鍵帽,不畫陰影與邊框,按下時只亮一下。 */
    flat: Boolean = false,
    onClick: () -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed && !flat) 0.92f else 1f, tween(80), label = "keyScale")
    Box(
        modifier
            .scale(scale)
            .then(if (flat) Modifier else Modifier.shadow(if (pressed) 1.dp else 6.dp, shape))
            .clip(shape)
            .background(if (pressed) facePressed else face)
            .then(if (flat) Modifier else Modifier.border(BorderStroke(1.dp, KeyEdge), shape))
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClickLabel = description, onClick = onClick),
        contentAlignment = Alignment.Center,
        content = content,
    )
}

@Composable
private fun RoundKey(
    icon: ImageVector,
    description: String,
    size: Dp = 56.dp,
    face: Color = KeyFace,
    facePressed: Color = KeyFacePressed,
    onClick: () -> Unit,
) {
    PressableKey(CircleShape, Modifier.size(size), face, facePressed, description, onClick = onClick) {
        Icon(icon, contentDescription = description, tint = KeyText, modifier = Modifier.size(size * 0.45f))
    }
}

/** 圓鍵 + 下方的小字說明;鍵面放圖示或簡短的按鍵名稱(例如「Esc」)。 */
@Composable
private fun LabeledRoundKey(label: String, keyText: String? = null, icon: ImageVector? = null, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        PressableKey(CircleShape, Modifier.size(56.dp), description = label, onClick = onClick) {
            if (icon != null) {
                Icon(icon, contentDescription = label, tint = KeyText, modifier = Modifier.size(24.dp))
            } else {
                Text(keyText ?: label, color = KeyText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(label, color = KeyTextMuted, fontSize = 11.sp)
    }
}

private data class DPadHints(val up: String, val down: String, val left: String, val right: String)

/** 方向盤:外圈四個方向(各自一塊可以按的區域),中間 OK 鍵。 */
@Composable
private fun DPad(
    onUp: () -> Unit,
    onDown: () -> Unit,
    onLeft: () -> Unit,
    onRight: () -> Unit,
    onOk: () -> Unit,
    okLabel: String? = null,
    okIcon: ImageVector? = null,
    hints: DPadHints? = null,
) {
    val ringSize = 228.dp
    Box(
        Modifier
            .size(ringSize)
            .shadow(10.dp, CircleShape)
            .clip(CircleShape)
            .background(Brush.radialGradient(listOf(Color(0xFF2A3040), DPadRing)))
            .border(BorderStroke(1.dp, KeyEdge), CircleShape),
    ) {
        val armShape = RoundedCornerShape(22.dp)
        DirectionArm(Icons.Filled.KeyboardArrowUp, hints?.up ?: "上", armShape, Modifier.align(Alignment.TopCenter).padding(top = 8.dp).size(84.dp, 66.dp), onUp, hint = hints?.up, hintBelow = true)
        DirectionArm(Icons.Filled.KeyboardArrowDown, hints?.down ?: "下", armShape, Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp).size(84.dp, 66.dp), onDown, hint = hints?.down, hintBelow = false)
        DirectionArm(Icons.AutoMirrored.Filled.KeyboardArrowLeft, hints?.left ?: "左", armShape, Modifier.align(Alignment.CenterStart).padding(start = 8.dp).size(66.dp, 84.dp), onLeft)
        DirectionArm(Icons.AutoMirrored.Filled.KeyboardArrowRight, hints?.right ?: "右", armShape, Modifier.align(Alignment.CenterEnd).padding(end = 8.dp).size(66.dp, 84.dp), onRight)

        PressableKey(
            CircleShape,
            Modifier.align(Alignment.Center).size(92.dp),
            face = BrandColors.IndigoDeep,
            facePressed = Color(0xFF3B33B8),
            description = okLabel ?: "播放/暫停",
            onClick = onOk,
        ) {
            if (okIcon != null) {
                Icon(okIcon, contentDescription = null, tint = Color.White, modifier = Modifier.size(38.dp))
            } else {
                Text(okLabel ?: "OK", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
        }

    }
}

/** 方向盤的一個方向。[hint] 是用途跟一般方向鍵不同時(對方是 Android)標在箭頭旁邊的小字。 */
@Composable
private fun DirectionArm(
    icon: ImageVector,
    description: String,
    shape: Shape,
    modifier: Modifier,
    onClick: () -> Unit,
    hint: String? = null,
    hintBelow: Boolean = true,
) {
    PressableKey(shape, modifier, face = Color.Transparent, facePressed = Color(0x22FFFFFF), description = description, flat = true, onClick = onClick) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (hint != null && !hintBelow) Text(hint, color = KeyTextMuted, fontSize = 10.sp)
            Icon(icon, contentDescription = description, tint = KeyText, modifier = Modifier.size(if (hint != null) 28.dp else 34.dp))
            if (hint != null && hintBelow) Text(hint, color = KeyTextMuted, fontSize = 10.sp)
        }
    }
}

/** 搖桿鍵:直立的長條,上半按「+」、下半按「−」,中間刻著用途(VOL/PG)。 */
@Composable
private fun Rocker(
    label: String,
    onUp: () -> Unit,
    onDown: () -> Unit,
    upIcon: ImageVector = Icons.Filled.Add,
    downIcon: ImageVector = Icons.Filled.Remove,
) {
    val shape = RoundedCornerShape(30.dp)
    Column(
        Modifier
            .width(60.dp)
            .shadow(6.dp, shape)
            .clip(shape)
            .background(KeyFace)
            .border(BorderStroke(1.dp, KeyEdge), shape),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PressableKey(RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp), Modifier.fillMaxWidth().height(62.dp), face = Color.Transparent, facePressed = Color(0x22FFFFFF), description = "$label 上", flat = true, onClick = onUp) {
            Icon(upIcon, contentDescription = null, tint = KeyText, modifier = Modifier.size(26.dp))
        }
        Text(label, color = KeyTextMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        PressableKey(RoundedCornerShape(bottomStart = 30.dp, bottomEnd = 30.dp), Modifier.fillMaxWidth().height(62.dp), face = Color.Transparent, facePressed = Color(0x22FFFFFF), description = "$label 下", flat = true, onClick = onDown) {
            Icon(downIcon, contentDescription = null, tint = KeyText, modifier = Modifier.size(26.dp))
        }
    }
}

/** 電視遙控器那種紅/綠/黃/藍的功能鍵:上面一條色塊,下面是用途與按鍵名稱。 */
@Composable
private fun ColorKey(label: String, keyText: String, color: Color, modifier: Modifier, onClick: () -> Unit) {
    PressableKey(RoundedCornerShape(12.dp), modifier.height(56.dp), description = label, onClick = onClick) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(width = 26.dp, height = 5.dp).clip(RoundedCornerShape(3.dp)).background(color))
            Spacer(Modifier.height(6.dp))
            Text(label, color = KeyText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text(keyText, color = KeyTextMuted, fontSize = 9.sp)
        }
    }
}

@Composable
private fun PillKey(label: String, modifier: Modifier, onClick: () -> Unit) {
    PressableKey(RoundedCornerShape(50), modifier.height(40.dp), description = label, onClick = onClick) {
        Text(label, color = KeyText, fontSize = 13.sp)
    }
}
