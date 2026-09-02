package com.meister.nexusradar.browser

/**
 * JavaScript is evaluated only inside the page the user opened in the embedded WebView.
 * It does not read passwords, form values or cookies.
 */
object NexusPageParser {
    val parseCurrentMod: String = """
        (function() {
          const result = {
            kind: 'mod', mod_id: 0, name: '', author: null, version: null,
            category: null, summary: null, published_at: null, updated_at: null,
            adult: false, url: location.href, tags: [], requirements: [], required_by: [],
            content_type: 'MOD', diagnostics: []
          };
          const text = (el) => el ? (el.textContent || '').replace(/\\s+/g,' ').trim() : '';
          const uniq = (a) => [...new Set(a.filter(Boolean))];
          const hrefId = (href) => {
            const m = (href || '').match(/\/skyrimspecialedition\/mods\/(\d+)/i);
            return m ? Number(m[1]) : 0;
          };
          result.mod_id = hrefId(location.href);
          if (!result.mod_id) result.diagnostics.push('mod_id_missing');

          const ld = [...document.querySelectorAll('script[type="application/ld+json"]')].map(x => {
            try { return JSON.parse(x.textContent); } catch(e) { return null; }
          }).filter(Boolean);
          const metas = (p) => document.querySelector('meta[property="' + p + '"]')?.content || null;
          const named = (n) => document.querySelector('meta[name="' + n + '"]')?.content || null;

          result.name = text(document.querySelector('h1')) || metas('og:title') || document.title.replace(/ at Skyrim Special Edition Nexus.*$/i,'').trim();
          result.summary = named('description') || metas('og:description') || null;

          const authorLink = [...document.querySelectorAll('a[href*="/users/"]')].find(a => text(a).length > 1);
          result.author = text(authorLink) || null;

          const categoryLink = [...document.querySelectorAll('a')].find(a => /\/mods\/categories\//i.test(a.getAttribute('href') || ''));
          result.category = text(categoryLink) || null;

          result.tags = uniq([...document.querySelectorAll('a')]
            .filter(a => /\/mods\/tags\//i.test(a.getAttribute('href') || ''))
            .map(text));

          const body = document.body?.innerText || '';
          const versionPatterns = [
            /(?:^|\\n)Version\\s*[:\\n]?\\s*([^\\n]{1,40})/i,
            /Current version\\s*[:\\n]?\\s*([^\\n]{1,40})/i
          ];
          for (const re of versionPatterns) { const m = body.match(re); if (m) { result.version = m[1].trim(); break; } }

          const normalizeDate = (v) => {
            if (!v) return null;
            const d = new Date(v);
            return isNaN(d.getTime()) ? v.trim() : d.toISOString();
          };
          const times = [...document.querySelectorAll('time')].map(t => ({raw: t.getAttribute('datetime'), label: text(t.parentElement)}));
          for (const t of times) {
            if (/upload|publish|created/i.test(t.label) && !result.published_at) result.published_at = normalizeDate(t.raw);
            if (/update|last updated/i.test(t.label) && !result.updated_at) result.updated_at = normalizeDate(t.raw);
          }
          const pub = body.match(/(?:Uploaded|Published|Created)\\s*[:\\n]?\\s*([^\\n]{3,80})/i);
          const upd = body.match(/(?:Updated|Last updated)\\s*[:\\n]?\\s*([^\\n]{3,80})/i);
          if (!result.published_at && pub) result.published_at = normalizeDate(pub[1]);
          if (!result.updated_at && upd) result.updated_at = normalizeDate(upd[1]);

          result.adult = /adult content|adult-only|adult only/i.test(body) ||
                         document.querySelector('[data-adult-content="true"],[class*="adult-content" i]') !== null;

          function linksNearHeading(pattern) {
            const headings = [...document.querySelectorAll('h1,h2,h3,h4,h5,strong')].filter(h => pattern.test(text(h)));
            const out = [];
            for (const h of headings) {
              let box = h.closest('section,article,div,li') || h.parentElement;
              let hops = 0;
              while (box && hops < 4 && box.querySelectorAll('a[href*="/skyrimspecialedition/mods/"]').length === 0) { box = box.parentElement; hops++; }
              if (!box) continue;
              for (const a of box.querySelectorAll('a[href*="/skyrimspecialedition/mods/"]')) {
                const id = hrefId(a.href); const name = text(a);
                if (id && id !== result.mod_id && name) out.push({mod_id:id, name:name, url:a.href});
              }
            }
            const seen = new Set();
            return out.filter(x => !seen.has(x.mod_id) && seen.add(x.mod_id));
          }
          result.requirements = linksNearHeading(/Nexus requirements|Requirements/i);
          result.required_by = linksNearHeading(/Mods requiring this file|Mods requiring/i);

          if (!result.name) result.diagnostics.push('name_missing');
          if (!result.version) result.diagnostics.push('version_missing');
          if (!result.category) result.diagnostics.push('category_missing');
          if (!result.published_at) result.diagnostics.push('published_at_missing');
          if (!result.updated_at) result.diagnostics.push('updated_at_missing');
          if (!result.requirements.length) result.diagnostics.push('requirements_empty_or_not_visible');
          if (!result.required_by.length) result.diagnostics.push('required_by_empty_or_not_visible');
          return JSON.stringify(result);
        })();
    """.trimIndent()

    val collectVisibleModLinks: String = """
        (function() {
          const seen = new Set();
          const items = [];
          for (const a of document.querySelectorAll('a[href*="/skyrimspecialedition/mods/"]')) {
            const m = a.href.match(/\/skyrimspecialedition\/mods\/(\d+)/i);
            if (!m) continue;
            const id = Number(m[1]);
            if (!id || seen.has(id)) continue;
            seen.add(id);
            items.push({mod_id:id, url:a.href.split('?')[0].split('#')[0], name:(a.textContent||'').replace(/\\s+/g,' ').trim()});
          }
          const next = document.querySelector('a[rel="next"]') || [...document.querySelectorAll('a')].find(a => /^(next|weiter|›|→)$/i.test((a.textContent||'').trim()) && /page=/i.test(a.href||''));
          return JSON.stringify({kind:'links', url:location.href, next_url: next ? next.href : null, links:items});
        })();
    """.trimIndent()
}
