package com.nshd.geminifreellm.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape

private sealed interface MarkdownBlock {
    data class Paragraph(val value:String):MarkdownBlock
    data class Heading(val level:Int,val value:String):MarkdownBlock
    data class Bullet(val depth:Int,val value:String):MarkdownBlock
    data class Numbered(val depth:Int,val marker:String,val value:String):MarkdownBlock
    data class Quote(val value:String):MarkdownBlock
    data class Code(val language:String,val value:String):MarkdownBlock
    data class Table(val rows:List<List<String>>):MarkdownBlock
}

@Composable
fun MarkdownMessage(text:String,error:Boolean,onCopyCode:(String)->Unit){
    val blocks=remember(text){parseMarkdown(text.take(500_000))}
    SelectionContainer{
        Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
            blocks.forEach{block->
                when(block){
                    is MarkdownBlock.Paragraph->InlineMarkdown(block.value,error)
                    is MarkdownBlock.Heading->{
                        val size=when(block.level){1->25.sp;2->22.sp;3->20.sp;4->18.sp;else->16.sp}
                        BasicText(block.value,color=LocalAppColors.current.text,fontSize=size,lineHeight=(size.value+6).sp,fontWeight=FontWeight.SemiBold)
                    }
                    is MarkdownBlock.Bullet->ListLine("•",block.depth,block.value,error)
                    is MarkdownBlock.Numbered->ListLine(block.marker,block.depth,block.value,error)
                    is MarkdownBlock.Quote->QuoteLine(block.value,error)
                    is MarkdownBlock.Code->CodeBlock(block,onCopyCode)
                    is MarkdownBlock.Table->TableBlock(block,error)
                }
            }
        }
    }
}

@Composable
private fun InlineMarkdown(value:String,error:Boolean){
    val colors=LocalAppColors.current
    val uriHandler=LocalUriHandler.current
    val annotated=remember(value,error){inlineAnnotated(value,colors)}
    ClickableText(text=annotated,style=TextStyle(color=if(error)colors.error else colors.text,fontSize=15.sp,lineHeight=23.sp),onClick={offset->
        annotated.getStringAnnotations("URL",offset,offset).firstOrNull()?.item?.let{url->
            if(url.startsWith("https://")||url.startsWith("http://"))runCatching{uriHandler.openUri(url)}
        }
    },modifier=Modifier.fillMaxWidth())
}

@Composable
private fun ListLine(marker:String,depth:Int,value:String,error:Boolean){
    Row(Modifier.fillMaxWidth().padding(start=(depth*14).dp),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.Top){
        BasicText(marker,color=LocalAppColors.current.accent,fontSize=14.sp,fontWeight=FontWeight.SemiBold)
        InlineMarkdown(value,error)
    }
}

@Composable
private fun QuoteLine(value:String,error:Boolean){
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)){
        Box(Modifier.width(3.dp).fillMaxHeight().background(LocalAppColors.current.accent))
        InlineMarkdown(value,error)
    }
}

@Composable
private fun CodeBlock(block:MarkdownBlock.Code,onCopyCode:(String)->Unit){
    val c=LocalAppColors.current
    Column(Modifier.fillMaxWidth().background(c.surface,RoundedCornerShape(12.dp)).border(1.dp,c.border,RoundedCornerShape(12.dp))){
        Row(Modifier.fillMaxWidth().height(48.dp).padding(start=12.dp,end=4.dp),verticalAlignment=Alignment.CenterVertically){
            BasicText(block.language.ifBlank{"code"},color=c.muted,fontSize=10.sp,fontWeight=FontWeight.Medium,modifier=Modifier.weight(1f))
            AppIconButton("copy","Copy code", onClick = { onCopyCode(block.value) })
        }
        Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(12.dp)){BasicText(block.value,color=c.text,fontSize=13.sp,lineHeight=19.sp,fontFamily=FontFamily.Monospace)}
    }
}

@Composable
private fun TableBlock(block:MarkdownBlock.Table,error:Boolean){
    val c=LocalAppColors.current
    Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())){
        Column(Modifier.border(1.dp,c.border,RoundedCornerShape(8.dp))){
            block.rows.forEachIndexed{rowIndex,row->
                Row{row.forEach{cell->Box(Modifier.width(160.dp).border(0.5.dp,c.border).padding(8.dp)){BasicText(cell,color=if(error)c.error else c.text,fontSize=12.sp,lineHeight=18.sp,fontWeight=if(rowIndex==0)FontWeight.Medium else null)}}}
            }
        }
    }
}

private fun parseMarkdown(source:String):List<MarkdownBlock>{
    val lines=source.replace("\r\n","\n").split('\n')
    val result=mutableListOf<MarkdownBlock>()
    var codeMode=false
    var language=""
    val fence="```"
    val code=StringBuilder()
    var i=0
    while(i<lines.size){
        val line=lines[i]; val trimmed=line.trim()
        if(codeMode){
            if(trimmed.startsWith(fence)){result+=MarkdownBlock.Code(language,code.toString().trimEnd());code.setLength(0);language="";codeMode=false}
            else{if(code.isNotEmpty())code.append('\n');code.append(line)}
            i++;continue
        }
        if(trimmed.startsWith(fence)){codeMode=true;language=trimmed.removePrefix(fence).trim().take(24);i++;continue}
        if(trimmed.isBlank()){i++;continue}
        if(trimmed.contains("|")&&i+1<lines.size&&isTableSeparator(lines[i+1])){
            val rows=mutableListOf<List<String>>();rows+=splitTableLine(line);i+=2
            while(i<lines.size&&lines[i].contains("|")){rows+=splitTableLine(lines[i]);i++}
            result+=MarkdownBlock.Table(rows);continue
        }
        val hashes=trimmed.takeWhile{it=='#'}.length
        if(hashes in 1..6&&trimmed.length>hashes&&trimmed[hashes]==' '){result+=MarkdownBlock.Heading(hashes,trimmed.substring(hashes+1));i++;continue}
        val leading=line.takeWhile{it==' '||it=='\t'}.length;val tail=line.drop(leading)
        if(tail.length>2&&(tail[0]=='-'||tail[0]=='+'||tail[0]=='*')&&tail[1].isWhitespace()){result+=MarkdownBlock.Bullet(leading/2,tail.substring(2).trim());i++;continue}
        val marker=tail.takeWhile{it.isDigit()||it=='.'||it==')'}
        if(marker.length>1&&marker.dropLast(1).all{it.isDigit()}&&(marker.last()=='.'||marker.last()==')')&&tail.length>marker.length&&tail[marker.length].isWhitespace()){result+=MarkdownBlock.Numbered(leading/2,marker,tail.substring(marker.length).trim());i++;continue}
        if(trimmed.startsWith(">")){result+=MarkdownBlock.Quote(trimmed.removePrefix(">").trim());i++;continue}
        result+=MarkdownBlock.Paragraph(trimmed);i++
    }
    if(codeMode&&code.isNotEmpty())result+=MarkdownBlock.Code(language,code.toString())
    return result
}

private fun isTableSeparator(line:String):Boolean{
    val cells=line.trim().removePrefix("|").removeSuffix("|").split('|')
    return cells.size>=2&&cells.all{cell->val clean=cell.trim().replace(":","");clean.count{it=='-'}>=3&&clean.all{it=='-'||it.isWhitespace()}}
}
private fun splitTableLine(line: String): List<String> = line.trim().removePrefix("|").removeSuffix("|").split("|").map { it.trim() }

private fun inlineAnnotated(source: String, colors: AppColors): AnnotatedString {
    val builder = AnnotatedString.Builder()
    val fence = 96.toChar()
    var i = 0

    fun appendStyled(style: SpanStyle, value: String) {
        builder.pushStyle(style)
        builder.append(value)
        builder.pop()
    }

    while (i < source.length) {
        if (source[i] == '[') {
            val close = source.indexOf("](", i + 1)
            if (close > i) {
                val endUrl = source.indexOf(')', close + 2)
                if (endUrl > close + 2) {
                    val label = source.substring(i + 1, close)
                    val url = source.substring(close + 2, endUrl)
                    builder.pushStringAnnotation("URL", url)
                    appendStyled(
                        SpanStyle(
                            color = colors.accent,
                            textDecoration = TextDecoration.Underline,
                            fontWeight = FontWeight.Medium
                        ),
                        label
                    )
                    builder.pop()
                    i = endUrl + 1
                    continue
                }
            }
        }

        if (source.startsWith("~~", i)) {
            val endStrike = source.indexOf("~~", i + 2)
            if (endStrike > i + 2) {
                appendStyled(
                    SpanStyle(textDecoration = TextDecoration.LineThrough),
                    source.substring(i + 2, endStrike)
                )
                i = endStrike + 2
                continue
            }
        }

        if (source.startsWith("**", i) || source.startsWith("__", i)) {
            val token = source.substring(i, i + 2)
            val endStrong = source.indexOf(token, i + 2)
            if (endStrong > i + 2) {
                appendStyled(
                    SpanStyle(fontWeight = FontWeight.SemiBold),
                    source.substring(i + 2, endStrong)
                )
                i = endStrong + 2
                continue
            }
        }

        if (source[i] == fence) {
            val endCode = source.indexOf(fence, i + 1)
            if (endCode > i + 1) {
                appendStyled(
                    SpanStyle(fontFamily = FontFamily.Monospace, background = colors.elevated),
                    source.substring(i + 1, endCode)
                )
                i = endCode + 1
                continue
            }
        }

        if (source[i] == '*' || source[i] == '_') {
            val endItalic = source.indexOf(source[i], i + 1)
            if (endItalic > i + 1) {
                appendStyled(
                    SpanStyle(fontStyle = FontStyle.Italic),
                    source.substring(i + 1, endItalic)
                )
                i = endItalic + 1
                continue
            }
        }

        builder.append(source[i])
        i++
    }

    return builder.toAnnotatedString()
}
