## NimoGo v1.0.5

在 NimoGo 里直接输入地名，现在和 Google Maps 的搜索框一样好用：中文、日文都能搜（「东京站」「上野」都能找到）。

**改动**
- 「输入地点」改用 Google 地点搜索（Places API (New) Autocomplete）：优先你所在的国家、离你近的地方，按距离列出名称、地址、距离，自己选。
- 选中的地点直接交给 Google 导航算路线；确认画面显示地址和距离，离得太远会用红字提醒。
- 隐私说明更新（输入的地名和当前位置会发给 Google 地点搜索），打开 app 时会再确认一次。

**要做的设置（只用「输入地点」才需要）**
- 在 Google Cloud 控制台、Navigation SDK 所在的同一个项目里启用 **Places API (New)**，并在 API Key 的「API 限制」里勾上它。
- 每搜一次算一次 Autocomplete 请求，有每月免费额度，以 Google 的价格表为准；可以在「配额」里设每日上限。
- 没启用时会提示原因，改用手机系统的地址查询（中文地名可能查不到）。从 Google Maps 共享过来的地点不受影响，也不额外收费。

**使用前**
- 导航要用你自己的 Google Maps Platform API Key（启用 Navigation SDK，要绑结算账号；每月前 1000 个目的地免费）。设置步骤见 README。
- 先强行停止 Nimo 官方 app，再用 NimoGo 连接眼镜。
- 从旧版本直接覆盖安装即可，设置不会丢。

**已知限制**
- 个人测试版本，只在 Android 16 上实测过导航。
- 非官方项目，与 Nimo 厂商、Google 都没有关系。
