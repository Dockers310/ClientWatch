# ClientWatch 2.0.1 — структура исходников

```text
src/main/java/dev/antifreecam/
├── AntiFreecam.java
├── command/
│   └── AfcCommand.java
├── detection/
│   └── DetectionManager.java
├── manager/
│   ├── AccessManager.java
│   ├── ProfileManager.java
│   └── PumpkinManager.java
├── util/
│   └── SchedulerUtil.java
└── gui/
    ├── AccessGui.java
    ├── FlagGui.java
    ├── Gui.java
    ├── GuiListener.java
    ├── HelpGui.java
    ├── HistoryGui.java
    ├── MainMenu.java
    ├── ModsGui.java
    ├── PlayersGui.java
    ├── PumpkinEditGui.java
    ├── PumpkinGui.java
    ├── SearchPrompt.java
    └── WatchGui.java
```

## 2.0.1

Добавлена полноценная совместимость с Paper и Folia 26.2:

- `plugin.yml`: `api-version: '26.2'` и `folia-supported: true`.
- Все отложенные действия с игроками переведены на `EntityScheduler`.
- Периодическая серверная задача использует `GlobalRegionScheduler`.
- Файловые сохранения выполняются через `AsyncScheduler`, чтобы не блокировать регион.
- Состояние онлайн-игроков и внутренние коллекции переведены на потокобезопасные структуры.
- Проверки, ответы PacketEvents и уведомления корректно передаются в регион владельца игрока.
- `reload` и обновление стандартного списка модов выполняются на global region.
- PacketEvents 2.13.0 остаётся зависимостью для Minecraft 26.2/Folia.
