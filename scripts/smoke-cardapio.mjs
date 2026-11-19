// Extensao do smoke oficial; somente fixture real descartavel.
export async function prepararCardapio({
  fixture,
  evaluate,
  wait,
  cdp,
  pause,
  pass,
  artifacts,
  fs,
  path,
}) {
  const click = (text) =>
    evaluate(
      '([...document.querySelectorAll("#cardapio-view button")].find(b=>b.textContent===' +
        JSON.stringify(text) +
        ")).click();true",
    );
  await evaluate("document.querySelector('[data-page=produtos]').click();true");
  await wait(
    "!document.getElementById('cardapio-view').hidden && !!document.querySelector('#cardapio-view [role=status]') && [...document.querySelectorAll('#cardapio-view button')].some(b=>b.textContent==='Nova categoria')",
    "administracao real do cardapio",
  );
  await click("Nova categoria");
  await evaluate(
    "document.querySelector('[data-field=categoria-nome]').value='Categoria smoke';document.querySelector('#cardapio-view form').requestSubmit();true",
  );
  await wait(
    "!document.querySelector('#cardapio-view form') && document.getElementById('cardapio-view').textContent.includes('Categoria smoke')",
    "categoria persistida",
  );
  async function product(name, sector, stock, price) {
    await click("Novo produto ou combo");
    await evaluate(
      'document.querySelector("[data-field=produto-nome]").value=' +
        JSON.stringify(name) +
        ';document.querySelector("[data-field=produto-preco]").value=' +
        JSON.stringify(price) +
        ';document.querySelector("[data-field=produto-setor]").value=' +
        JSON.stringify(sector) +
        ';document.querySelector("[data-field=produto-estoque]").checked=' +
        stock +
        ';document.querySelector("#cardapio-view form").requestSubmit();true',
    );
    await wait(
      '!document.querySelector("#cardapio-view form") && [...document.querySelectorAll("#cardapio-view .attendance-card h3")].some(h=>h.textContent===' +
        JSON.stringify(name) +
        ")",
      "produto persistido",
    );
  }
  await product("Prato smoke", "COZINHA", true, "12,50");
  await product("Bebida smoke", "BAR", false, "5,00");
  await evaluate(
    "[...document.querySelectorAll('#cardapio-view .attendance-card')].find(c=>c.querySelector('h3').textContent==='Prato smoke').querySelectorAll('button')[2].click();true",
  );
  await wait(
    "!!document.querySelector('[data-field=estoque-quantidade]')",
    "ajuste autorizado de estoque",
  );
  await evaluate(
    "document.querySelector('[data-field=estoque-quantidade]').value='5';document.querySelector('[data-field=estoque-motivo]').value='Contagem sintetica do smoke';document.querySelector('#cardapio-view form').requestSubmit();true",
  );
  await wait(
    "!document.querySelector('#cardapio-view form') && document.getElementById('cardapio-view').textContent.includes('Quantidade: 5')",
    "estoque real ajustado",
  );
  await evaluate(
    "[...document.querySelectorAll('#cardapio-view .attendance-card')].find(c=>c.querySelector('h3').textContent==='Prato smoke').querySelector('button').click();true",
  );
  await wait(
    "!!document.querySelector('[data-field=produto-preco]')",
    "editor de preco",
  );
  await evaluate(
    "document.querySelector('[data-field=produto-preco]').value='13,75';document.querySelector('#cardapio-view form').requestSubmit();true",
  );
  await wait(
    "!document.querySelector('#cardapio-view form') && document.getElementById('cardapio-view').textContent.includes('R$ 13,75')",
    "preco decimal persistido",
  );
  await evaluate("[...document.querySelectorAll('#cardapio-view .attendance-card')].find(c=>c.querySelector('h3').textContent==='Prato smoke').querySelectorAll('button')[1].click();true");
  await wait("document.getElementById('cardapio-view').textContent.includes('0 → 5') && document.getElementById('cardapio-view').textContent.includes('Contagem sintetica do smoke')",'historico real de quantidades e motivo');
  await click('Fechar histórico');
  await click("Novo produto ou combo");
  await evaluate(
    "document.querySelector('[data-field=produto-nome]').value='Combo smoke';document.querySelector('[data-field=produto-preco]').value='20,00';var type=document.querySelector('[data-field=produto-tipo]');type.value='COMBO';type.dispatchEvent(new Event('change'));document.querySelectorAll('#cardapio-view input[data-product]').forEach(c=>c.checked=true);document.querySelector('#cardapio-view form').requestSubmit();true",
  );
  await wait(
    "!document.querySelector('#cardapio-view form') && document.querySelectorAll('#cardapio-view .attendance-card').length===3",
    "combo misto real com preco proprio",
  );
  for (const [name, width, height] of [
    ["desktop", 1366, 900],
    ["tablet", 768, 1024],
    ["mobile", 360, 800],
  ]) {
    await cdp("Emulation.setDeviceMetricsOverride", {
      width,
      height,
      deviceScaleFactor: 1,
      mobile: width < 500,
    });
    await pause(100);
    if (!(await evaluate("document.documentElement.scrollWidth<=innerWidth+2")))
      throw Error("Cardapio transborda " + name);
    const shot = await cdp("Page.captureScreenshot", {
      format: "png",
      captureBeyondViewport: true,
    });
    await fs.writeFile(
      path.join(artifacts, "cardapio-admin-" + name + ".png"),
      Buffer.from(shot.data, "base64"),
    );
  }
  await click("Copiar cardápio");
  await evaluate(
    'document.querySelector("#cardapio-view form select").value=' +
      JSON.stringify(fixture.destinoId) +
      ';document.querySelector("#cardapio-view form").requestSubmit();true',
  );
  await wait(
    "[...document.querySelectorAll('#cardapio-view button')].some(b=>b.textContent==='Confirmar cópia')",
    "previa real de copia",
  );
  await click("Confirmar cópia");
  await wait(
    "document.getElementById('cardapio-view').textContent.includes('Cópia confirmada')",
    "copia persistida e auditada",
  );
  await evaluate(
    'document.getElementById("establishment-select").value=' +
      JSON.stringify(fixture.destinoId) +
      ';document.getElementById("establishment-select").dispatchEvent(new Event("change"));true',
  );
  await wait(
    "!document.getElementById('app-view').hidden && document.querySelectorAll('#cardapio-view .attendance-card').length===3 && document.getElementById('cardapio-view').textContent.includes('Quantidade: 0')",
    "copia sem estoque fisico",
  );
  await cdp("Page.reload");
  await wait(
    "!document.getElementById('app-view').hidden && !document.getElementById('cardapio-view').hidden && document.querySelectorAll('#cardapio-view .attendance-card').length===3",
    "F5 preserva produtos e unidade B",
  );
  if (
    (await evaluate(
      'document.getElementById("establishment-select").value',
    )) !== fixture.destinoId
  )
    throw Error("F5 perdeu unidade do cardapio");
  await evaluate(
    'document.getElementById("establishment-select").value=' +
      JSON.stringify(fixture.estabelecimentoId) +
      ';document.getElementById("establishment-select").dispatchEvent(new Event("change"));true',
  );
  await wait(
    "!document.getElementById('app-view').hidden && document.getElementById('cardapio-view').textContent.includes('Quantidade: 5')",
    "estoque da origem preservado",
  );
  pass(
    "2.5: categoria, produto cozinha/bar, preco exato, ajuste, combo, copia sem estoque e F5/unidades reais; desktop/tablet/360px",
  );
  await evaluate(
    "document.querySelector('[data-page=funcionarios]').click();true",
  );
  await wait(
    "!document.getElementById('employee-view').hidden && !!document.querySelector('.employee-table')",
    "retorno a equipe",
  );
}
export async function smokeCardapioOperacional({
  evaluate,
  wait,
  cdp,
  pause,
  pass,
  artifacts,
  fs,
  path,
}) {
  await evaluate(
    "[...document.querySelectorAll('.attendance-area button')].find(b=>b.textContent==='Cardápio').click();true",
  );
  await wait(
    "document.querySelector('.attendance-area h3')?.textContent==='Cardápio da unidade' && document.querySelectorAll('.attendance-area .attendance-card').length===3",
    "cardapio consultado por PIN",
  );
  if (
    !(await evaluate(
      "document.querySelector('.attendance-area').textContent.includes('R$ 13,75') && document.querySelector('.attendance-area').textContent.includes('Combo smoke')",
    ))
  )
    throw Error("Cardapio operacional incorreto");
  await cdp("Page.reload");
  await wait(
    "!document.getElementById('op-protected').hidden && document.querySelector('.attendance-area h3')?.textContent==='Cardápio da unidade'",
    "F5 restaura consulta de cardapio com sessao real",
  );
  for (const [name, width, height] of [
    ["desktop", 1366, 900],
    ["tablet", 768, 1024],
    ["mobile", 360, 800],
  ]) {
    await cdp("Emulation.setDeviceMetricsOverride", {
      width,
      height,
      deviceScaleFactor: 1,
      mobile: width < 500,
    });
    await pause(100);
    if (!(await evaluate("document.documentElement.scrollWidth<=innerWidth+2")))
      throw Error("Consulta cardapio transborda " + name);
    const shot = await cdp("Page.captureScreenshot", {
      format: "png",
      captureBeyondViewport: true,
    });
    await fs.writeFile(
      path.join(artifacts, "cardapio-operacional-" + name + ".png"),
      Buffer.from(shot.data, "base64"),
    );
  }
  pass(
    "2.5: consulta operacional real por PIN, produto e combo, precos, F5 e tres resolucoes",
  );
  await evaluate(
    "[...document.querySelectorAll('.attendance-area button')].find(b=>b.textContent==='Mesas').click();true",
  );
  await wait(
    "!!document.querySelector('.attendance-area .attendance-card')",
    "retorno as mesas",
  );
}
