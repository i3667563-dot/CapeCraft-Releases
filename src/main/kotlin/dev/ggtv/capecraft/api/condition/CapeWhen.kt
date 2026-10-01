package dev.ggtv.capecraft.api.condition

import dev.ggtv.capecraft.CapeCraftLog
import dev.ggtv.capecraft.api.CAPE_RUNTIME_API_VERSION
import dev.ggtv.kjen.CrenError
import dev.ggtv.kjen.Value
import dev.ggtv.koren.WorldContext
import dev.ggtv.koren.WorldRoot
import java.util.concurrent.ConcurrentHashMap

/**
 * Ответ «прочитать нечем».
 *
 * Объявлено здесь, а не в [dev.ggtv.capecraft.condition.Condition], потому что
 * нужно в обе стороны: читателю аддонного корня (публичный API) и разбору
 * условия. Тянуть `Condition` в API нельзя — он тянет за собой koren, а API
 * аддона от него не зависит.
 */
const val UNKNOWN = "unknown"

/**
 * Поле корня `when`, объявленного аддоном.
 *
 * @property name имя поля, как его пишут в конфиге: `burning`
 * @property doc что поле значит; показывается в редакторе и в `/capecraft list`
 * @property values закрытый список значений; пустой — поле любого значения
 *   (число, id из реестра модов, произвольная строка)
 * @property numeric приходит ли значение числом, а значит применимы ли `>`, `<`, `..`
 */
data class CapeWhenField(
    val name: String,
    val doc: String = "",
    val values: List<String> = emptyList(),
    val numeric: Boolean = false,
)

/**
 * Чтение поля аддонного корня.
 *
 * [subject] — сущность игрока, состояние которого читается (в реализации
 * CapeCraft это `net.minecraft.world.entity.Entity`/`PlayerEntity`, но тип
 * здесь не MC: общий код и LSP о Minecraft не знают, а аддон читает сущность
 * уже своими, версионными классами). `null` — читать нечего: контекст чужого
 * игрока или серверный.
 *
 * Ожидаемое значение — [Value]: строка, целое или дробное. Булево поле
 * отдаётся строкой `"true"`/`"false"`, ровно как у встроенных корней.
 */
fun interface CapeWhenReader {
    fun read(subject: Any?, field: String): Value
}

/**
 * Корень `when`, объявленный аддоном: `fire.burning`, `myaddon.level`.
 *
 * ## Что аддон получает
 *
 * ```kotlin
 * api.whenConditions.register(
 *     CapeWhenRoot(
 *         root = "fire",
 *         doc = "Горит ли игрок.",
 *         defaultField = "burning",
 *         fields = listOf(
 *             CapeWhenField("burning", "Горит ли игрок.", values = listOf("true", "false")),
 *         ),
 *     ) { subject, field -> Value.VStr(if ((subject as? Entity).isOnFire) "true" else "false") },
 * )
 * ```
 *
 * ## Любой аддонный корень self-only, и флага для этого нет
 *
 * Флага `selfOnly` здесь нет намеренно, и это не недоработка, а единственно
 * честный вариант. Наблюдатель не знает состояния чужого игрока: он видит
 * плащ и клетки инвентаря, а не «горит ли он». Состояние — это всегда что-то
 * про сущность, а сущность наблюдателя — только его собственная.
 *
 * Провод с этим ничего не может: [dev.ggtv.capecraft.sync.WireRoot] —
 * закрытый перечень встроенных корней, и аддонного корня в нём нет и не
 * будет без новой версии протокола. Отсюда [dev.ggtv.capecraft.sync.WireCondition.from]
 * возвращает `null`, и провайдер с аддонным условием обязан быть self-only
 * ([dev.ggtv.capecraft.provider.Provider.isSelfOnly]) — иначе он молча
 * выпадает из объявления, и человек удивляется, почему его плащ не появился у
 * соседа.
 */
data class CapeWhenRoot(
    val root: String,
    val doc: String = "",
    val defaultField: String? = null,
    val fields: List<CapeWhenField>,
    val reader: CapeWhenReader,
)

/**
 * Реестр аддонных корней `when`.
 *
 * Читает их [dev.ggtv.capecraft.condition.Condition] при разборе `when { … }`:
 * встроенный корень берётся из [WorldRoot], аддонный — отсюда. Ошибка
 * неизвестного корня перечисляет и встроенные, и аддонные, иначе человек
 * ищет опечатку там, где её нет.
 */
class CapeWhenRegistry {
    private val roots = LinkedHashMap<String, CapeWhenRoot>()

    /**
     * Зарегистрировать корень (повторная регистрация перезаписывает).
     *
     * Проверки в `require`, а не в лог: кривое объявление — ошибка аддона, а
     * аддон без работающего корня `when` бесполезен, и продолжать молча хуже,
     * чем упасть на его же `register`.
     */
    @Synchronized
    fun register(root: CapeWhenRoot) {
        val name = root.root
        require(name.isNotBlank()) { "имя аддонного корня when не может быть пустым" }
        require(!name.contains('.')) {
            "«$name»: имя корня when не может содержать точку — точка разделяет корень и поле"
        }
        require(WorldRoot.bySegment(name) == null) {
            "«$name» — встроенный корень when; свой корень не переопределяет встроенный"
        }
        require(root.fields.isNotEmpty()) { "у корня when «$name» нет ни одного поля" }
        val names = root.fields.map { it.name }
        require(names.none { it.isBlank() }) { "у корня when «$name» пустое имя поля" }
        require(names.none { it.contains('.') }) {
            "у корня when «$name» имя поля не может содержать точку: ${names.joinToString()}"
        }
        require(names.distinct().size == names.size) {
            "у корня when «$name» поле повторяется: ${names.joinToString()}"
        }
        val def = root.defaultField
        require(def == null || def in names) {
            "поле по умолчанию «$def» у корня when «$name» не объявлено среди ${names.joinToString()}"
        }
        roots[name] = root
    }

    /** Корень по имени сегмента; `null` — такого корня нет. */
    @Synchronized
    operator fun get(name: String): CapeWhenRoot? = roots[name]

    /** Имена всех зарегистрированных корней, в порядке регистрации. */
    @Synchronized
    fun names(): List<String> = roots.keys.toList()

    @Synchronized
    fun size(): Int = roots.size

    fun apiVersion(): Int = CAPE_RUNTIME_API_VERSION

    /** Снять все регистрации — нужно тестам, которые меняют глобальный реестр. */
    @Synchronized
    fun clear() = roots.clear()
}

/**
 * Реестр корней `when`, доступный без `CapeApi`.
 *
 * Отдельный держатель нужен, а не поле в [dev.ggtv.capecraft.api.CapeApi], по
 * двум причинам.
 *
 * Первая — разные читатели. Аддон регистрирует корни в [CapeApi] через
 * entrypoint, а разбирает конфиг [dev.ggtv.capecraft.provider.ProviderLoader]
 * уже по другому классу; связать их через `CapeApiHolder` означало бы, что
 * разбор `when` тянет за собой весь фасад API, а вместе с ним версионный
 * `CapeRenderModifierRegistry`. Редактор (LSP) компилирует те же исходники без
 * Minecraft, и лишняя связь там — риск упасть на загрузке класса там, где
 * Minecraft нет вовсе.
 *
 * Вторая — природа данных. Встроенные корни приезжают из вендоренного
 * [WorldRoot], и это константы, известные и моду, и редактору. Аддонные
 * приезжают из реестра мода в одном процессе, а в редакторе — из дескриптора
 * `capecraft-addon.kn` внутри jar'а аддона. Значит, у корня две независимые
 * формы: значение ([CapeWhenRoot] в моде) и описание (дескриптор в редакторе),
 * и общий реестр — единственное, что может быть у них обоим.
 */
object CapeWhenRoots {

    /** Реестр, в котором живут корни всех аддонов этого процесса. */
    val registry = CapeWhenRegistry()

    /**
     * Прочитанные и упавшие корни: жалоба в лог один раз за корень.
     *
     * Условие считается каждый кадр, и падение читателя без такой защиты
     * засыпало бы лог тысячами одинаковых строк — а именно этот лог человек
     * читает, чтобы понять, почему плащ не появился.
     */
    private val reported = ConcurrentHashMap.newKeySet<String>()

    /**
     * Прочитать поле аддонного корня в контексте, который этому умеет.
     *
     * Вызывается из `addonField` версионного [WorldContext]: контекст владеет
     * и сущностью, и тем, какие корни в нём вообще наблюдаемы. Общего кода
     * без Minecraft здесь быть не может — читатель аддона получает живую
     * сущность, а её тип версионный.
     *
     * @throws CrenError.NotFound такого корня нет или поле для него не читается
     */
    fun read(world: WorldContext, root: String, field: String, path: String): Value {
        val spec = registry[root] ?: throw CrenError.NotFound(path)
        return try {
            spec.reader.read(world.subjectEntity(), field)
        } catch (e: Exception) {
            // Читатель аддона — чужой код, и падать из-за него на каждом кадре
            // нельзя: «не прочиталось» для условия честнее падения. В лог —
            // один раз за корень, иначе кадр за кадром одно и то же.
            reportOnce(root, e)
            Value.VStr(UNKNOWN)
        }
    }

    /** Отчитаться об ошибке чтения корня — один раз за корень. */
    fun reportOnce(root: String, error: Throwable) {
        if (reported.add(root)) {
            CapeCraftLog.LOGGER.error(
                "CapeCraft: чтение корня when «$root» упало, корень считается непрочитанным: ${error.message}",
                error,
            )
        }
    }

    /** Снять регистрации и отметки об ошибках — нужно тестам. */
    fun clear() {
        registry.clear()
        reported.clear()
    }
}