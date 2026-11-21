package com.mogutofu.aimcmarket.command;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;

import java.util.concurrent.CompletableFuture;

/**
 * 允许中文的参数类型。
 *
 * <p>Brigadier 自带的 {@code word()}/{@code string()} 在"不带引号"时只认 ASCII 字母数字，
 * 所以 {@code /ex buy 铁锭 3} 这种中文参数会报"参数后应有空格分隔，但发现了尾随数据"。
 * 这个类型按"空格分隔"读一段任意文本（中文、标点都可以），够市场命令用了。</p>
 */
public final class CnStringArgument implements ArgumentType<String> {
    private CnStringArgument() {
    }

    public static CnStringArgument create() {
        return new CnStringArgument();
    }

    @Override
    public String parse(StringReader reader) throws CommandSyntaxException {
        int start = reader.getCursor();
        while (reader.canRead() && reader.peek() != ' ') {
            reader.skip();
        }
        String value = reader.getString().substring(start, reader.getCursor());
        if (value.isEmpty()) {
            throw CommandSyntaxException.BUILT_IN_EXCEPTIONS.dispatcherUnknownArgument().createWithContext(reader);
        }
        return value;
    }

    @Override
    public <S> CompletableFuture<Suggestions> listSuggestions(CommandContext<S> context, SuggestionsBuilder builder) {
        return builder.buildFuture();
    }

    @Override
    public String toString() {
        return "cnstring";
    }
}
