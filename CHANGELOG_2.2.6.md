# ClientWatch 2.2.6

- Исправлена окраска предметов в меню модов игрока: статус из «Запрошенных модов» теперь имеет приоритет и применяется к любому источнику обнаружения.
- Исправлена визуализация списка игроков: наличие ClientWatch Companion теперь заметно по `[CW]`, отдельной строке и свечению предмета.
- В карточке файла явно подписано, где SHA-512, где SHA-1 и где CurseForge fingerprint.
- При добавлении мода теперь явно указано, что он попадает в раздел «Запрошенные моды».
- Усилена обработка кликов GUI: верхний инвентарь определяется через rawSlot, а обновление меню после действий откладывается на 1 тик, чтобы уменьшить пропуски ЛКМ/ПКМ.

- Исправлено падение `WatchRegistry.prefer()` при первом совпадении проекта. В консоли оно проявлялось как `Could not pass event InventoryClickEvent to ClientWatch v2.2.5` со стеком `WatchRegistry.prefer -> WatchRegistry.classify -> ModsGui.open -> GuiListener.onClick`. Из-за этой ошибки меню модов могло не открываться, а ЛКМ/ПКМ выглядели как пропущенные.
- Исправлено описание проверки: если совпал именно SHA-1, GUI больше не называет его SHA-512.

## Ошибка из консоли, исправленная в 2.2.6

При открытии меню модов сервер сообщал:

```text
[02:49:02 ERROR]: Could not pass event InventoryClickEvent to ClientWatch v2.2.5
    at ClientWatch.jar//dev.antifreecam.watch.WatchRegistry.prefer(WatchRegistry.java:189)
    at ClientWatch.jar//dev.antifreecam.watch.WatchRegistry.classify(WatchRegistry.java:114)
    at ClientWatch.jar//dev.antifreecam.gui.ModsGui.classification(ModsGui.java:163)
    at ClientWatch.jar//dev.antifreecam.gui.ModsGui.open(ModsGui.java:96)
    at ClientWatch.jar//dev.antifreecam.gui.GuiListener.onClick(GuiListener.java:194)
```

Причина: при первом совпадении проекта `prefer()` получал первый аргумент `null` и обращался к `a.status()`. Теперь первый аргумент корректно обрабатывается как пустой кандидат.

### Исправление сборки и фонового обновления GUI
- Добавлен отсутствовавший импорт `dev.antifreecam.util.SchedulerUtil` в `ModsGui.java`.
- Это исправляет ошибку компиляции `cannot find symbol: variable SchedulerUtil` в строке фонового обновления меню модов.
