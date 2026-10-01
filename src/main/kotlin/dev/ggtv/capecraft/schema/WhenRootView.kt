package dev.ggtv.capecraft.schema

import dev.ggtv.koren.WorldRoot

/**
 * Корень `when` так, как его видит редактор: встроенный или объявленный
 * аддонным дескриптором.
 *
 * ## Зачем слой, если есть [WorldRoot]
 *
 * Встроенные корни — это перечисление в [WorldRoot], и редактор знает его
 * целиком. Аддонных корней в перечислении быть не может: мод и редактор
 * собираются из одних исходников, а список корней аддонов приходит из данных
 * (`capecraft-addon.kn` в jar'е), которые компилятор не видел.
 *
 * Поэтому [dev.ggtv.capecraft.ide.CrenAnalyzer] работает не с [WorldRoot], а с
 * этим интерфейсом: у встроенного корня всё берётся из [WhenSchema], у
 * аддонного — из дескриптора. Формы две, а код подсказок один, и иначе
 * каждое место с `when` знало бы, аддон это или нет.
 *
 * Порядок и приоритет — в [WhenSchema.rootViews]: встроенные всегда впереди.
 */
sealed interface WhenRootView {

    /** Сегмент корня, как его пишут в конфиге: `biome`, `fire`. */
    val segment: String

    /**
     * Что корню вообще значит, одной строкой.
     *
     * У встроенных корней пусто: их словарь и так на весь экран, а вот у
     * аддонного без описания подсказка выглядит как «корень условия `fire`.»
     * и ничем не отличается от опечатки, разобрать которую нечем.
     */
    val doc: String

    /** Поля корня в порядке объявления. */
    val fields: List<String>

    /** Поле, которое подставляется, если корень написан без точки. */
    val defaultField: String?

    /** Короткие записи корня: `snowy` -> `precipitation = snow`. */
    val aliases: List<String>

    /** Описание поля для подсказки; `null` — такого поля нет. */
    fun docFor(field: String): String?

    /** Допустимые значения поля; пусто — значение не ограничено. */
    fun valuesOf(field: String): List<String>

    /** Приходит ли поле числом, а значит ли применимы `>`, `<`, `..`. */
    fun isNumeric(field: String): Boolean
}

/** Встроенный корень: всё берётся из рантайма через [WhenSchema]. */
class BuiltinWhenRoot(val root: WorldRoot) : WhenRootView {
    override val segment: String get() = root.segment

    /** У встроенных корней описания корня нет — см. [WhenRootView.doc]. */
    override val doc: String get() = ""
    override val fields: List<String> get() = WhenSchema.fieldsOf(root)
    override val defaultField: String? get() = WhenSchema.defaultFieldOf(root)
    override val aliases: List<String> get() = WhenSchema.aliasesOf(root)
    override fun docFor(field: String): String? = WhenSchema.docFor(root, field)
    override fun valuesOf(field: String): List<String> = WhenSchema.valuesOf(root, field)
    override fun isNumeric(field: String): Boolean = WhenSchema.isNumeric(root, field)
}

/**
 * Корень, объявленный аддонным дескриптором.
 *
 * [origin] и [addon] нужны не подсказке, а человеку, который читает ошибку:
 * без них «корень `fire` неизвестен» не сказано, из какого jar'а он взят.
 */
data class AddonWhenRootView(
    val root: AddonWhenRoot,
    val addon: String,
    val origin: String,
) : WhenRootView {

    override val segment: String get() = root.id

    override val doc: String get() = root.doc

    override val fields: List<String> get() = root.fields.map { it.name }

    override val defaultField: String? get() = root.defaultField

    /**
     * Синонимов у аддонного корня нет, и это не недоработка.
     *
     * Синоним — это короткая запись, которую рантайм умеет **развернуть**: у
     * встроенных корней он и разворачивается, потому что список значений и
     * правило подстановки живут в [dev.ggtv.koren.WorldRoot] и известны обоим
     * сторонам. Аддон своего правила на провод не отдаёт, а выдуманная тут
     * пара (`подсказка предлагает "yes"`) в игре развернулась бы в поле,
     * которого у корня нет, и условие не совпало бы никогда.
     */
    override val aliases: List<String> get() = emptyList()

    override fun docFor(field: String): String? =
        root.fields.firstOrNull { it.name == field }?.doc?.takeIf { it.isNotEmpty() }

    override fun valuesOf(field: String): List<String> =
        root.fields.firstOrNull { it.name == field }?.values.orEmpty()

    override fun isNumeric(field: String): Boolean =
        root.fields.firstOrNull { it.name == field }?.numeric == true
}