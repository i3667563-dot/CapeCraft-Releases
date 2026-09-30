package dev.ggtv.capecraft.condition

import dev.ggtv.capecraft.CapeConfigEnv
import dev.ggtv.capecraft.schema.Placeholders
import dev.ggtv.kjen.Value
import dev.ggtv.koren.WorldRoot

/**
 * Условие по переменным: блок `if { ... }` в словаре провайдера.
 *
 * Работает ровно как [Condition] (те же операторы, то же AND, тот же принцип
 * «недоступное значение = не подошло, а не ошибка»), но сравнивает не поля
 * живого мира, а **переменные**. Разница смысловая: `when` отвечает на
 * «где стоит тот, чей плащ считается», `if` — на «про что этот игрок».
 * Для чужого плаща оба считаются против наблюдаемого игрока, поэтому
 * `if { username: "Eonixx" }` на его наборе сработает у того, кто смотрит на
 * Eonixx, и не сработает на мне.
 *
 * ## Переменные двух видов
 *
 * 1. **Плейсхолдеры** — `username`, `uuid`, `name`, `root` и всё, что
 *    зарегистрировал аддон. Те же имена, что и в `{...}` внутри `url`/`path`,
 *    и резолвятся тем же [Placeholders], поэтому список не расходится сам.
 * 2. **Значения** — `$ИМЯ` (и `-D` с тем же именем), ровно та же запись,
 *    что и подстановка окружения в значениях конфига. Позволяет вести себя
 *    по-разному на prod- и dev-запуске без правки конфига.
 *
 * Обе разновидности — обычные скаляры, поэтому работает весь операторный
 * набор `when`: `=`, `!`, `>`, `>=`, `<`, `<=`, `a..b`. Числом считается и
 * строка, разбираемая как число, — иначе `$PORT: ">8000"` молчал бы в
 * никуда, а переменная окружения всегда строка.
 */
data class VarCondition(val predicates: List<VarPredicate>) {

    /** Выполняется ли условие (AND по всем предикатам). */
    fun matches(ctx: Placeholders.Context): Boolean = predicates.all { it.matches(ctx) }

    /** Есть ли хоть одно условие: пустой блок `if = { }` — «всегда». */
    val isEmpty: Boolean get() = predicates.isEmpty()

    companion object {
        /** Префикс переменной окружения, как в подстановке в значениях. */
        const val ENV_PREFIX = "$"

        /**
         * Имена переменных, о которых мод знает сам: плейсхолдеры и `$ИМЯ`.
         *
         * Для диагностики в редакторе и сообщений об ошибках. Список аддонных
         * переменных сюда не входит намеренно: аддоны появляются позже и
         * меняются от запуска к запуску, а перечислять их в ошибке значит
         * показывать неправду.
         */
        val KNOWN: List<String> = listOf(
            "username", "uuid", "name", "root", "$" + "ИМЯ",
        )

        /**
         * Имена, которые можно вписать руками и которые всегда найдут значение.
         *
         * Отдельно от [KNOWN] потому, что там `$ИМЯ` — описание семейства имён
         * для текста ошибки, а не ключ: вставить `$ИМЯ` в конфиг нельзя.
         * Подсказки вставляют только то, что в конфиг попадёт.
         */
        val COMPLETABLE: List<String> = listOf("username", "uuid", "root")

        /**
         * Разобрать блок `if { ... }` из словаря. Пустой словарь — пустое условие.
         *
         * Ключ — имя переменной целиком: точка в нём значима (`myAddon.level`),
         * а не разделитель пути, как в `when`. Поэтому `if { a.b: "1" }` ищет
         * переменную с именем `a.b`, а не раскладывает ключ на «корень.поле».
         * Ключ с `$` — ссылка на переменную окружения, и подстановки на разборе
         * для него не происходит: парсер отдаёт её как есть (см. [VarSource]).
         */
        fun parse(dict: Value.VDict): VarCondition {
            val predicates = dict.pairs.map { (key, value) -> parsePredicate(key, value) }
            return VarCondition(predicates)
        }

        private fun parsePredicate(key: String, value: Value): VarPredicate {
            val name = key.trim()
            if (name.isEmpty()) {
                throw IllegalArgumentException("условие if: пустое имя переменной")
            }
            rejectWorldRoot(name)
            if (name.startsWith(ENV_PREFIX) && VarSource.envKey(name) == null) {
                // Молча «никогда не совпадёт» — худший вид поломки: условие
                // выглядит живым, а провайдер не выберется никогда, и непонятно
                // почему. Ошибка на загрузке сразу показывает опечатку.
                throw IllegalArgumentException(
                    "условие if: «$name» — не имя переменной окружения; " +
                        "после «$» нужны буквы, цифры и «_» (или фигурные скобки: «${'$'}{ИМЯ}»)",
                )
            }
            val (op, expected) = Condition.parseValueFor("if", value)
            return VarPredicate(name, op, expected)
        }

        /**
         * Отвергнуть корень мира, попавший в `if`.
         *
         * Самая вероятная ошибка — перенести рабочее `when { weather: "rain" }`
         * в `if` и ждать того же. Молча такое имя не переменная: условие просто
         * никогда не выполнилось бы, и провайдер тихо ушёл бы в fallback.
         *
         * Проверяется **только** этот закрытый список. Аддонные плейсхолдеры
         * заранее неизвестны, и проверять «есть такая переменная» здесь нельзя
         * было бы сломать их: неизвестное имя — обычное дело и означает лишь
         * «условие не выполнено» ([VarPredicate.matches]).
         */
        private fun rejectWorldRoot(name: String) {
            val segment = name.substringBefore('.')
            if (WorldRoot.bySegment(segment) == null) return
            throw IllegalArgumentException(
                "условие if: «$segment» — корень мира, а не переменная. " +
                    "Так проверяется в when { $segment: ... }; в if кладут имя " +
                    "переменной (${KNOWN.joinToString()})",
            )
        }

    }
}

/**
 * Предикат по переменной: имя + операция + ожидаемое значение.
 *
 * Имя хранится строкой, а не отдельным типом: на провод оно уходит как есть
 * (см. `WireVarPredicate`), и разбирать его обратно должен получатель, у
 * которого свой набор аддонов.
 */
data class VarPredicate(
    val name: String,
    val op: Op,
    val expected: Expected,
) {
    /** Проверка одного предиката. Отсутствующая переменная — «не подошло». */
    fun matches(ctx: Placeholders.Context): Boolean {
        val raw = VarSource.resolve(name, ctx) ?: return false
        return op.apply(VarSource.asValue(raw), expected)
    }
}

/**
 * Источник значений переменных для `if`.
 *
 * Отдельный объект, а не код внутри [VarPredicate], потому что резолв должен
 * быть один на всех: и для локального отбора, и для пришедшего по сети
 * объявления. Расхождение в двух копиях означало бы, что один и тот же
 * провайдер считается по-разному в зависимости от пути, по которому пришёл.
 */
object VarSource {

    /**
     * Значение переменной или `null`, если её нет.
     *
     * `$ИМЯ` читает **сырое** `ИМЯ` из окружения JVM или из `-D`, без
     * префикса `CAPECRAFT_`: префикс принадлежит [CapeConfigEnv] и служит
     * внутренним опциям мода, а `$PROFILE` в конфиге плаща — это
     * `PROFILE=prod` в оболочке, как его писал бы кто угодно.
     * Второй источник — `-D` с тем же именем, потому что у Minecraft-модов
     * флаги `-D` копируют дословно и ждать `CAPECRAFT_`-формы нельзя.
     *
     * Пишется ровно так же, как подстановка окружения в значениях конфига
     * (`$NAME` и `${NAME}`), но **не подставляется на разборе**: в ключе
     * условия подстановка превратила бы ключ в значение и имя пропало бы.
     * Поэтому парсер отдаёт `$NAME` как есть, а чтением занимается мод — и
     * делает это при выборе провайдера, а не при загрузке файла.
     *
     * Всё, что без `$`, — в [Placeholders], поэтому аддонная переменная
     * работает в `if` ровно тогда, когда работает в `{...}`.
     */
    fun resolve(name: String, ctx: Placeholders.Context): String? =
        resolveWith(name, ctx, { System.getenv(it) }, { System.getProperty(it) })

    /**
     * То же, но с подменёнными источниками окружения.
     *
     * Существует ради тестов: `System.getenv` в юнит-тесте недоступен, а
     * проверять числовые сравнения по переменной окружения иначе нечем.
     * Тот же приём, что у `Tokenizer.tokenizeWithEnv`.
     */
    internal fun resolveWith(
        name: String,
        ctx: Placeholders.Context,
        env: (String) -> String?,
        prop: (String) -> String?,
    ): String? {
        if (name.startsWith(VarCondition.ENV_PREFIX)) {
            val key = envKey(name) ?: return null
            return env(key) ?: prop(key)
        }
        return Placeholders.resolveOrNull(name, ctx)
    }

    /**
     * Имя переменной окружения из `$NAME` или `${NAME}`.
     *
     * `null`, если за `$` не имя: у `$` без имени и у `${}` значения нет, и
     * выдумывать имя нельзя. Скобки снимаются, дефолт `${NAME:-x}` в ключе
     * условия не имеет смысла и остаётся частью имени — то есть не найдётся,
     * и условие просто не выполнится.
     */
    internal fun envKey(name: String): String? {
        var key = name.removePrefix(VarCondition.ENV_PREFIX)
        if (key.startsWith("{") && key.endsWith("}")) {
            key = key.substring(1, key.length - 1)
        }
        if (key.isEmpty()) return null
        val valid = (key[0].isLetter() || key[0] == '_') &&
            key.drop(1).all { it.isLetterOrDigit() || it == '_' }
        return if (valid) key else null
    }

    /**
     * Строка из окружения — это всегда строка, но `$PORT: ">8000"`
     * обязан работать. Поэтому число в строке читается как число: иначе
     * любой числовой оператор над переменной окружения был бы враньём,
     * а выглядел бы как «условие не сработало» без всякой ошибки.
     */
    fun asValue(raw: String): Value {
        val s = raw.trim()
        s.toLongOrNull()?.let { return Value.VInt(it) }
        s.toDoubleOrNull()?.let { return Value.VFloat(it) }
        return Value.VStr(raw)
    }
}
