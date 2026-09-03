import { expect, test, type Page } from '@playwright/test';

async function expectBrowserStorageToBeEmpty(page: Page) {
  const storageSize = await page.evaluate(() => ({
    localStorage: window.localStorage.length,
    sessionStorage: window.sessionStorage.length,
  }));

  expect(storageSize).toEqual({ localStorage: 0, sessionStorage: 0 });
}

test.describe(
  'ODI Lineage Explorer — demo flow',
  { tag: ['@Integration', '@Discovery'] },
  () => {
    test('opens a load plan and inspects context-resolved mapping metadata', async ({
      page,
    }) => {
      await test.step('Open an in-memory demo session', async () => {
        await page.goto('/');

        await expect(
          page.getByRole('heading', { name: 'Połączenie z ODI' })
        ).toBeVisible();
        const openDemoButton = page.getByRole('button', {
          name: 'Otwórz demo',
        });
        await expect(openDemoButton).toBeVisible();
        await expect(openDemoButton).toBeEnabled();
        await openDemoButton.click();

        await expect(
          page.getByRole('heading', { name: 'Load Plany', exact: true })
        ).toBeVisible();
        await expect(
          page.getByLabel('Aktywne repozytorium')
        ).toContainText('ODI_DEMO_WORK');
        await expectBrowserStorageToBeEmpty(page);
      });

      await test.step('Open a load plan in the production context', async () => {
        const openLoadPlanButton = page.getByRole('button', {
          name: 'Otwórz load plan LP_DAILY_SALES',
        });
        await expect(openLoadPlanButton).toBeVisible();
        await expect(openLoadPlanButton).toBeEnabled();
        await openLoadPlanButton.click();

        await expect(
          page.getByRole('heading', { name: 'LP_DAILY_SALES' })
        ).toBeVisible();

        const hierarchy = page.getByRole('list', {
          name: 'Hierarchia kroków Load Planu',
        });
        const collapseParallel = hierarchy.getByRole('button', {
          name: 'Zwiń Parallel parallel_sales',
        });
        await expect(collapseParallel).toBeVisible();
        await expect(collapseParallel).toBeEnabled();
        await expect(collapseParallel).toHaveAttribute('aria-expanded', 'true');
        await collapseParallel.click();

        const expandParallel = hierarchy.getByRole('button', {
          name: 'Rozwiń Parallel parallel_sales',
        });
        await expect(expandParallel).toHaveAttribute('aria-expanded', 'false');
        await expect(hierarchy.getByText('SCN_LOAD_ORDERS')).toBeHidden();
        await expect(expandParallel).toBeEnabled();
        await expandParallel.click();

        await expect(collapseParallel).toHaveAttribute('aria-expanded', 'true');
        await expect(hierarchy.getByText('SCN_LOAD_ORDERS')).toBeVisible();

        const contextSelect = page.getByLabel('Context podglądu');
        await expect(contextSelect).toBeVisible();
        await expect(contextSelect).toBeEnabled();
        await contextSelect.selectOption('PRD');
        await expect(contextSelect).toHaveValue('PRD');

        const openMappingButton = page.getByRole('button', {
          name: 'Pokaż lineage MAP_LOAD_ORDERS',
        });
        await expect(openMappingButton).toBeVisible();
        await expect(openMappingButton).toBeEnabled();
        await openMappingButton.click();
      });

      await test.step('Inspect canonical and context-resolved mapping metadata', async () => {
        await expect(
          page.getByRole('heading', { name: 'MAP_LOAD_ORDERS' })
        ).toBeVisible();
        const sources = page.getByRole('list', { name: 'Źródła mappingu' });
        const targets = page.getByRole('list', { name: 'Targety mappingu' });
        await expect(sources).toBeVisible();
        await expect(targets).toBeVisible();
        await expect(sources.getByText('ORDERS', { exact: true })).toBeVisible();
        await expect(targets.getByText('ORDER_FACT', { exact: true })).toBeVisible();
        await expect(page.getByText('FILTER_ACTIVE')).toHaveCount(0);

        const sourceColumn = page.getByRole('button', {
          name: 'Pokaż wpływ kolumny ORDERS.CUSTOMER_ID',
        });
        const targetColumn = page.getByRole('button', {
          name: 'Pokaż źródła kolumny ORDER_FACT.CUSTOMER_KEY',
        });
        await sourceColumn.click();
        await expect(sourceColumn).toHaveAttribute('aria-pressed', 'true');
        await expect(targetColumn).toHaveAttribute('data-related', 'true');
        await expect(
          page.getByRole('status', { name: 'Podsumowanie zaznaczonej kolumny' })
        ).toContainText('ORDERS.CUSTOMER_ID wpływa na ORDER_FACT.CUSTOMER_KEY');

        await page.getByRole('button', { name: 'Powiększ graf' }).click();
        await expect(
          page.getByRole('status', { name: 'Poziom powiększenia grafu' })
        ).toHaveText('125%');

        const metadata = page.getByRole('region', {
          name: 'Metadane zaznaczonego obiektu',
        });
        await expect(metadata).toBeVisible();
        await expect(metadata).toContainText('Alias w mappingu');
        await expect(metadata).toContainText('SRC_ORDERS');
        await expect(metadata).toContainText('Resource Name');
        await expect(metadata).toContainText('ORDERS');
        await expect(metadata).toContainText('Logical Schema');
        await expect(metadata).toContainText('LS_DWH');
        await expect(metadata).toContainText('Physical Schema');
        await expect(metadata).toContainText('DWH_PROD');
        await expect(metadata).toContainText('Data Server');
        await expect(metadata).toContainText('DATA-PROD-01');
        await expectBrowserStorageToBeEmpty(page);
      });

      await test.step('End the session without persisting credentials', async () => {
        const logoutButton = page.getByRole('button', {
          name: 'Zakończ sesję',
        });
        await expect(logoutButton).toBeVisible();
        await expect(logoutButton).toBeEnabled();
        await logoutButton.click();

        await expect(
          page.getByRole('heading', { name: 'Połączenie z ODI' })
        ).toBeVisible();
        await expect(
          page.getByLabel('Hasło schematu repozytorium', { exact: true })
        ).toHaveValue('');
        await expect(
          page.getByLabel('Hasło użytkownika ODI', { exact: true })
        ).toHaveValue('');
        await expectBrowserStorageToBeEmpty(page);
      });
    });
  }
);
