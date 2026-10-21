package io.github.yingdu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavLinksTest {
    @Test fun shareTextFromGoogleMaps() {
        val text = "東京駅 · 〒100-0005 東京都千代田区丸の内１丁目\nhttps://maps.app.goo.gl/AbCdEf123"
        assertEquals("https://maps.app.goo.gl/AbCdEf123", NavLinks.findUrl(text))
        assertEquals("東京駅", NavLinks.titleOf(text))
        assertTrue(NavLinks.isShortLink("https://maps.app.goo.gl/AbCdEf123"))
        assertFalse(NavLinks.isShortLink("https://www.google.com/maps/place/x"))
        assertEquals("", NavLinks.titleOf("https://maps.app.goo.gl/x"))
    }

    @Test fun placeUrlUsesPlaceCoordinatesNotViewport() {
        val url = "https://www.google.com/maps/place/%E6%9D%B1%E4%BA%AC%E9%A7%85/@35.6812,139.7671,17z/data=!3m1!4b1!4m6!3m5!1s0x60188bfbd89f700b:0x277c49ba34ed38!8m2!3d35.6812362!4d139.7671248!16zL20vMDFtX3Fw"
        val d = NavLinks.parseUrl(url)!!
        assertEquals("東京駅", d.title)
        assertEquals(35.6812362, d.lat!!, 1e-9)
        assertEquals(139.7671248, d.lng!!, 1e-9)
        assertTrue(d.ready)
    }

    @Test fun queryForms() {
        // 坐标
        NavLinks.parseUrl("https://maps.google.com/?q=35.6586,139.7454")!!.let {
            assertEquals(35.6586, it.lat!!, 1e-9); assertEquals(139.7454, it.lng!!, 1e-9)
        }
        // Place ID
        NavLinks.parseUrl("https://www.google.com/maps/search/?api=1&query=Tokyo%20Tower&query_place_id=ChIJCewJkL2LGGAR3Qmk0vCTGkg")!!.let {
            assertEquals("ChIJCewJkL2LGGAR3Qmk0vCTGkg", it.placeId); assertEquals("Tokyo Tower", it.title); assertTrue(it.ready)
        }
        NavLinks.parseUrl("https://maps.google.com/maps?q=place_id:ChIJabc", "店")!!.let { assertEquals("ChIJabc", it.placeId); assertEquals("店", it.title) }
        // 只有地名（短链接展开后常见：?q=名称+地址&ftid=…）：要再地理编码
        NavLinks.parseUrl("https://maps.google.com/maps?q=%E6%9D%B1%E4%BA%AC%E3%82%BF%E3%83%AF%E3%83%BC,+%E8%8A%9D%E5%85%AC%E5%9C%92&ftid=0x60188bbd:0x481a93f0d2a409dd")!!.let {
            assertEquals("東京タワー, 芝公園", it.query); assertFalse(it.ready)
        }
        // 路线链接的终点
        NavLinks.parseUrl("https://www.google.com/maps/dir/?api=1&destination=34.9671,135.7727")!!.let { assertEquals(34.9671, it.lat!!, 1e-9) }
        // 路线链接：终点是路径里最后一个地点，不是地图中心
        NavLinks.parseUrl("https://www.google.com/maps/dir/%E6%96%B0%E5%AE%BF%E9%A7%85/%E6%9D%B1%E4%BA%AC%E3%82%BF%E3%83%AF%E3%83%BC/@35.67,139.72,13z/data=!3m1")!!.let {
            assertEquals("東京タワー", it.query); assertFalse(it.ready)
        }
        NavLinks.parseUrl("https://www.google.com/maps/dir/35.1,135.1/35.2,135.3/@35.0,135.0,12z")!!.let { assertEquals(135.3, it.lng!!, 1e-9) }
        // 只有地图中心
        NavLinks.parseUrl("https://www.google.com/maps/@35.0,135.0,15z")!!.let { assertEquals(35.0, it.lat!!, 1e-9) }
        // 不是地图链接
        assertNull(NavLinks.parseUrl("https://example.com/?q=35,139"))
    }

    @Test fun geoUris() {
        NavLinks.parseGeo("geo:35.1,135.2?q=35.1,135.2(金閣寺)")!!.let { assertEquals("金閣寺", it.title); assertEquals(135.2, it.lng!!, 1e-9) }
        NavLinks.parseGeo("geo:0,0?q=%E6%B8%85%E6%B0%B4%E5%AF%BA")!!.let { assertEquals("清水寺", it.query); assertFalse(it.ready) }
        NavLinks.parseGeo("geo:34.99,135.78")!!.let { assertTrue(it.ready) }
        assertNull(NavLinks.parseGeo("geo:0,0"))
        assertNull(NavLinks.point("200,10"))
    }
}

class NavTextTest {
    @Test fun distances() {
        assertEquals("5 米", NavText.distance(4))
        assertEquals("85 米", NavText.distance(84))
        assertEquals("250 米", NavText.distance(247))
        assertEquals("1.0 公里", NavText.distance(996))
        assertEquals("1.2 公里", NavText.distance(1234))
        assertEquals("12 公里", NavText.distance(12_345))
    }

    @Test fun durations() {
        assertEquals("不到 1 分钟", NavText.duration(30))
        assertEquals("15 分钟", NavText.duration(15 * 60 + 10))
        assertEquals("1 小时 5 分", NavText.duration(65 * 60))
        assertEquals("2 小时", NavText.duration(120 * 60))
        val tz = java.util.TimeZone.getTimeZone("Asia/Tokyo")
        // 2026-10-20 12:00 JST + 32 分钟
        assertEquals("12:32", NavText.eta(1_792_465_200_000L, 32 * 60, tz))
    }
}

class NavIconTest {
    @Test fun maneuvers() {
        assertEquals(NavIcon.LEFT, NavIcon.of(6))
        assertEquals(NavIcon.RIGHT, NavIcon.of(7))
        assertEquals(NavIcon.DESTINATION, NavIcon.of(2))
        assertEquals(NavIcon.UTURN_RIGHT, NavIcon.of(14))
        assertEquals(NavIcon.ROUNDABOUT_CW, NavIcon.of(43))
        assertEquals(NavIcon.ROUNDABOUT_CCW, NavIcon.of(62))
        assertEquals(NavIcon.STRAIGHT, NavIcon.of(65))
        assertEquals(NavIcon.UNKNOWN, NavIcon.of(999))
        // 每种 SDK 的转向都有图标
        for (m in 1..65) assertTrue("maneuver $m", NavIcon.of(m) != NavIcon.UNKNOWN)
    }

    @Test fun displayKeyIgnoresSmallChanges() {
        fun snap(m: Int, sec: Int) = NavSnap(NavSnap.ENROUTE, NavStep(7, "外堀通り", "右折", 300), m, 60, 2000, sec, emptyList())
        assertEquals(snap(247, 900).displayKey(), snap(251, 905).displayKey())
        assertFalse(snap(247, 900).displayKey() == snap(230, 900).displayKey())
    }
}

class NavBudgetTest {
    @Test fun monthlyCount() {
        val tz = java.util.TimeZone.getTimeZone("Asia/Tokyo")
        assertEquals("2026-10", NavBudget.monthKey(1_792_465_200_000L, tz))
        assertEquals(5, NavBudget.used("2026-10", 5, "2026-10"))
        assertEquals(0, NavBudget.used("2026-09", 5, "2026-10"))
        assertTrue(NavBudget.allowed(999, 1000))
        assertFalse(NavBudget.allowed(1000, 1000))
        assertTrue(NavBudget.allowed(5000, 0))
    }
}

class NavRemainTest {
    @Test fun preferNavigatorTimeAndDistance() {
        val raw = NavSnap(NavSnap.ENROUTE, NavStep(6, "", "向左转", 15), 15, 12, 18_183_000, 14_747_700, emptyList())
        val fixed = NavLogicFix.remaining(raw, 300, 240)
        assertEquals(300, fixed.remainMeters)
        assertEquals(240, fixed.remainSeconds)
        assertEquals(15, fixed.toStepMeters)
        // 没有 Navigator 的值时：不合理的不显示，合理的照用
        assertEquals(null, NavLogicFix.remaining(raw, null, null).remainMeters)
        assertEquals(null, NavLogicFix.remaining(raw, 0, 0).remainSeconds)
        val ok = raw.copy(remainMeters = 300, remainSeconds = 240)
        assertEquals(ok, NavLogicFix.remaining(ok, null, null))
    }
}

class NavMapTest {
    private val route = listOf(NavPoint(35.6970, 139.7740), NavPoint(35.6979, 139.7740), NavPoint(35.6979, 139.769575))

    @Test fun snapAndHeading() {
        val (seg, on) = NavMap.snap(route, NavPoint(35.697765, 139.77401))
        assertEquals(0, seg)
        assertEquals(35.697765, on.lat, 1e-6)
        assertEquals(0.0, NavMap.headingAt(route, seg, on), 1.0)            // 往北走
        val (seg2, on2) = NavMap.snap(route, NavPoint(35.69791, 139.7720))
        assertEquals(1, seg2)
        assertEquals(270.0, NavMap.headingAt(route, seg2, on2), 1.0)        // 拐弯后往西
    }

    @Test fun courseHelpsAtCorners() {
        // 刚拐过路口 3 米、往西走：位置离旧路（南北向）和新路（东西向）差不多近，靠走路方向认到新路
        val p = NavPoint(35.6979, 139.7740 - 3 / 90_400.0)
        assertEquals(1, NavMap.snap(route, p, 270.0).first)
        assertEquals(270.0, NavMap.headingAt(route, 1, NavMap.snap(route, p, 270.0).second), 1.0)
        assertEquals(90.0, NavMap.angleDiff(0.0, 270.0), 0.001)
        assertEquals(10.0, NavMap.angleDiff(355.0, 5.0), 0.001)
    }

    @Test fun projectionHeadingUp() {
        val c = NavPoint(35.6970, 139.7740)
        // 往北 100 米、朝北：在中心正上方
        val (x, y) = NavMap.toScreen(c, NavPoint(35.6970 + 100 / 110_574.0, 139.7740), 0.0, 1.0, 100f, 100f)
        assertEquals(100f, x, 0.5f); assertEquals(0f, y, 0.5f)
        // 朝东时，北边的点跑到左边
        val (x2, y2) = NavMap.toScreen(c, NavPoint(35.6970 + 100 / 110_574.0, 139.7740), 90.0, 1.0, 100f, 100f)
        assertEquals(0f, x2, 0.5f); assertEquals(100f, y2, 0.5f)
    }

    @Test fun alongAndScale() {
        val (seg, on) = NavMap.snap(route, NavPoint(35.697765, 139.7740))
        val turn = NavMap.pointAlong(route, seg, on, 15.0)
        assertEquals(0.0, NavMap.dist(turn, route[1]), 0.5)
        assertEquals(0.5, NavMap.scaleFor(15, 100), 0.0)
        assertEquals(3.0, NavMap.scaleFor(200, 100), 0.0)
        assertEquals(8.0, NavMap.scaleFor(5000, 100), 0.0)
    }
}
