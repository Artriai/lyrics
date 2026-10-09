package com.lyricsplus.android.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.*

internal enum class AboutShape { HEART, STAR, LIKE }
internal data class AboutBurst(
    val id: Long, val origin: Offset, val born: Long, val strength: Float, val shape: AboutShape
)

private data class Vec3(val x: Float, val y: Float, val z: Float) {
    operator fun minus(other: Vec3) = Vec3(x - other.x, y - other.y, z - other.z)
    fun cross(other: Vec3) = Vec3(y * other.z - z * other.y, z * other.x - x * other.z, x * other.y - y * other.x)
    fun dot(other: Vec3) = x * other.x + y * other.y + z * other.z
    fun unit(): Vec3 {
        val length = sqrt(dot(this)).coerceAtLeast(.0001f)
        return Vec3(x / length, y / length, z / length)
    }
}

private class Rotation(ax: Float, ay: Float, az: Float) {
    private val cx = cos(ax); private val sx = sin(ax)
    private val cy = cos(ay); private val sy = sin(ay)
    private val cz = cos(az); private val sz = sin(az)
    fun apply(point: Vec3): Vec3 {
        val y = point.y * cx - point.z * sx
        val z = point.y * sx + point.z * cx
        val x = point.x * cy + z * sy
        return Vec3(x * cz - y * sz, x * sz + y * cz, -point.x * sy + z * cy)
    }
}

private data class Mesh(val vertices: List<Vec3>, val triangles: List<List<Int>>, val color: Color)
private data class PaintedFace(val points: List<Offset>, val depth: Float, val color: Color, val origin: Offset, val radius: Float, val alpha: Float)

private fun cross2(a: Offset, b: Offset, c: Offset) =
    (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)

/** Ear clipping preserves the concave heart notch, star valleys and thumb outline. */
private fun triangulate(points: List<Offset>): List<List<Int>> {
    val remaining = points.indices.toMutableList()
    val triangles = mutableListOf<List<Int>>()
    while (remaining.size > 3) {
        val ear = remaining.indices.firstOrNull { index ->
            val a = remaining[(index + remaining.size - 1) % remaining.size]
            val b = remaining[index]
            val c = remaining[(index + 1) % remaining.size]
            cross2(points[a], points[b], points[c]) > .000001f &&
                remaining.none { p ->
                    p != a && p != b && p != c &&
                        cross2(points[a], points[b], points[p]) >= -.000001f &&
                        cross2(points[b], points[c], points[p]) >= -.000001f &&
                        cross2(points[c], points[a], points[p]) >= -.000001f
                }
        } ?: error("Unable to triangulate about shape")
        triangles += listOf(
            remaining[(ear + remaining.size - 1) % remaining.size],
            remaining[ear], remaining[(ear + 1) % remaining.size]
        )
        remaining.removeAt(ear)
    }
    triangles += remaining.toList()
    return triangles
}

private fun beveledMesh(outline: List<Offset>, color: Color): Mesh {
    val area = outline.indices.sumOf { i ->
        val a = outline[i]; val b = outline[(i + 1) % outline.size]
        (a.x * b.y - b.x * a.y).toDouble()
    }
    val points = if (area < 0) outline.reversed() else outline
    val n = points.size
    val center = Offset(points.map { it.x }.average().toFloat(), points.map { it.y }.average().toFloat())
    val vertices = listOf(.84f to .19f, 1f to .065f, 1f to -.065f, .84f to -.19f).flatMap { (scale, depth) ->
        points.map { point -> Vec3(center.x + (point.x - center.x) * scale, center.y + (point.y - center.y) * scale, depth) }
    }
    val cap = triangulate(points)
    val triangles = cap.toMutableList()
    triangles += cap.map { face -> face.reversed().map { it + 3 * n } }
    fun quad(a: Int, b: Int, c: Int, d: Int) {
        triangles += listOf(a, b, c)
        triangles += listOf(a, c, d)
    }
    points.indices.forEach { i ->
        val j = (i + 1) % n
        quad(i, n + i, n + j, j)
        quad(n + i, 2 * n + i, 2 * n + j, n + j)
        quad(3 * n + j, 2 * n + j, 2 * n + i, 3 * n + i)
    }
    return Mesh(vertices, triangles, color)
}

private val meshes: Map<AboutShape, List<Mesh>> by lazy {
    val heart = (0 until 48).map { i ->
        val angle = (i * 2 * PI / 48).toFloat()
        Offset(sin(angle).pow(3), -(13 * cos(angle) - 5 * cos(2 * angle) - 2 * cos(3 * angle) - cos(4 * angle)) / 17f)
    }
    val star = (0 until 10).map { i ->
        val angle = (-PI / 2 + i * PI / 5).toFloat()
        val radius = if (i % 2 == 0) 1f else .46f
        Offset(cos(angle) * radius, sin(angle) * radius)
    }
    val thumb = listOf(
        Offset(-.52f, .79f), Offset(-.52f, -.04f), Offset(-.15f, -.49f),
        Offset(-.06f, -.91f), Offset(.07f, -1.03f), Offset(.24f, -1.01f),
        Offset(.35f, -.83f), Offset(.32f, -.59f), Offset(.23f, -.27f),
        Offset(.73f, -.27f), Offset(.88f, -.14f), Offset(.88f, .04f),
        Offset(.8f, .14f), Offset(.86f, .26f), Offset(.78f, .43f),
        Offset(.82f, .55f), Offset(.73f, .71f), Offset(.67f, .88f),
        Offset(.49f, .95f), Offset(-.24f, .95f)
    )
    val cuff = listOf(Offset(-.96f, -.11f), Offset(-.59f, -.11f), Offset(-.59f, .85f), Offset(-.96f, .85f))
    mapOf(
        AboutShape.HEART to listOf(beveledMesh(heart, Color(0xFFF34F6D))),
        AboutShape.STAR to listOf(beveledMesh(star, Color(0xFFFFC64D))),
        AboutShape.LIKE to listOf(beveledMesh(thumb, Color(0xFFFFC786)), beveledMesh(cuff, Color(0xFF719FFF)))
    )
}

private val light = Vec3(-.45f, -.65f, 1f).unit()
private val halfLight = Vec3(light.x, light.y, light.z + 1f).unit()
private fun litColor(base: Color, normal: Vec3): Color {
    val diffuse = .28f + .75f * max(0f, normal.dot(light))
    val specular = max(0f, normal.dot(halfLight)).pow(28) * .6f
    return Color(
        (base.red * diffuse + specular).coerceIn(0f, 1f),
        (base.green * diffuse + specular).coerceIn(0f, 1f),
        (base.blue * diffuse + specular).coerceIn(0f, 1f), 1f
    )
}

/** Extruded meshes with perspective, depth ordering, bevels and directional lighting. */
internal fun DrawScope.drawAboutTap3D(bursts: List<AboutBurst>, now: Long) {
    val faces = mutableListOf<PaintedFace>()
    for (burst in bursts) {
        val t = ((now - burst.born).coerceAtLeast(0L) / 1400f).coerceIn(0f, 1f)
        val pop = (1f - exp(-t * 13f) * cos(t * 23f)).coerceAtLeast(0f)
        val radius = 25 * density * burst.strength * pop
        val alpha = ((1f - t) / .3f).coerceIn(0f, 1f)
        if (radius < .1f || alpha <= 0f) continue
        val direction = if (burst.id % 2L == 0L) 1f else -1f
        val origin = burst.origin + Offset(direction * 10 * density * t, -28 * density * t * t)
        val rotation = Rotation(-.23f + sin(t * 7f) * .12f,
            direction * (-.6f + t * 1.1f + sin(t * 9f) * .12f), direction * sin(t * 8f) * .12f)
        drawOval(Color.Black.copy(alpha = .3f * alpha),
            origin + Offset(-radius * .65f, radius * .95f), Size(radius * 1.3f, radius * .15f))
        for (mesh in meshes.getValue(burst.shape)) {
            val vertices = mesh.vertices.map(rotation::apply)
            val projected = vertices.map { point ->
                val perspective = 4.5f / (4.5f - point.z)
                origin + Offset(point.x * radius * perspective, point.y * radius * perspective)
            }
            for (triangle in mesh.triangles) {
                val a = vertices[triangle[0]]; val b = vertices[triangle[1]]; val c = vertices[triangle[2]]
                val normal = (b - a).cross(c - a).unit()
                if (normal.z <= .001f) continue
                faces += PaintedFace(triangle.map { projected[it] }, (a.z + b.z + c.z) / 3,
                    litColor(mesh.color, normal), origin, radius, alpha)
            }
        }
    }
    faces.sortedBy { it.depth }.forEach { face ->
        val path = Path().apply {
            moveTo(face.points[0].x, face.points[0].y)
            lineTo(face.points[1].x, face.points[1].y)
            lineTo(face.points[2].x, face.points[2].y)
            close()
        }
        val top = Color((face.color.red * 1.05f).coerceAtMost(1f),
            (face.color.green * 1.05f).coerceAtMost(1f), (face.color.blue * 1.05f).coerceAtMost(1f), face.alpha)
        drawPath(path, Brush.linearGradient(listOf(top, face.color.copy(alpha = face.alpha)),
            face.origin + Offset(-face.radius, -face.radius), face.origin + Offset(face.radius, face.radius)))
    }
}
