## NimoGo v1.0.1

专注导航：去掉了 Google Gemini 相关的选项。

**改动**
- 「全天记忆」的转文字 / 总结服务里去掉 Google Gemini（用过 Gemini 的会自动换回默认服务，要重新选一下并填 Key）。
- 关于页、隐私说明、README 同步更新。

**导航（和 v1.0.0 相同）**
- 在 Google Maps 里选好地点 →「共享」→ NimoGo，眼镜上显示转向箭头、距离、路名、下一步、剩余距离和预计到达时间。步行 / 驾车 / 骑行（不支持公共交通）。
- 也可以在「导航」页直接输入地名；每次开始前确认，本机按月计数，可设每月上限。

**使用前**
- 导航要用你自己的 Google Maps Platform API Key（启用 Navigation SDK，要绑结算账号；每月前 1000 个目的地免费）。设置步骤见 README。
- 先强行停止 Nimo 官方 app，再用 NimoGo 连接眼镜。

**已知限制**
- 个人测试版本，只在 Android 16 上实测过导航。
- 非官方项目，与 Nimo 厂商、Google 都没有关系。
