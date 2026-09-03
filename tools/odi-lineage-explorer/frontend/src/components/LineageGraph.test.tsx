import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';

import type { MappingDetail } from '../api/types';
import { LineageGraph } from './LineageGraph';

const mapping: MappingDetail = {
  id: 'map-orders',
  name: 'MAP_LOAD_ORDERS',
  contextCode: 'DEV',
  warnings: [],
  nodes: [
    {
      id: 'orders-source',
      label: 'ORDERS',
      kind: 'DATASTORE_SOURCE',
      rawComponentType: 'DATASTORE_SOURCE',
      columns: [
        { id: 'orders-id', name: 'ORDER_ID' },
        { id: 'orders-customer-id', name: 'CUSTOMER_ID' },
      ],
      metadata: {
        alias: 'SRC_ORDERS',
        datastoreName: 'DS_ORDERS',
        resourceName: 'ORDERS',
        modelName: 'SALES',
        logicalSchema: 'SALES_LS',
        isPhysicalLocationResolved: true,
      },
    },
    {
      id: 'customers-source',
      label: 'CUSTOMERS',
      kind: 'DATASTORE_SOURCE',
      rawComponentType: 'DATASTORE_SOURCE',
      columns: [{ id: 'customers-id', name: 'CUSTOMER_ID' }],
      metadata: {
        alias: 'SRC_CUSTOMERS',
        datastoreName: 'DS_CUSTOMERS',
        resourceName: 'CUSTOMERS',
        modelName: 'CUSTOMER',
        logicalSchema: 'CUSTOMER_LS',
        isPhysicalLocationResolved: true,
      },
    },
    {
      id: 'order-fact-target',
      label: 'ORDER_FACT',
      kind: 'DATASTORE_TARGET',
      rawComponentType: 'DATASTORE_TARGET',
      columns: [
        { id: 'fact-order-id', name: 'ORDER_ID' },
        { id: 'fact-customer-key', name: 'CUSTOMER_KEY' },
      ],
      metadata: {
        alias: 'TGT_ORDER_FACT',
        datastoreName: 'DS_ORDER_FACT',
        resourceName: 'ORDER_FACT',
        modelName: 'DWH',
        logicalSchema: 'DWH_LS',
        isPhysicalLocationResolved: true,
      },
    },
  ],
  edges: [
    { id: 'table-orders-target', from: 'orders-source', to: 'order-fact-target' },
    { id: 'table-customers-target', from: 'customers-source', to: 'order-fact-target' },
  ],
  columnLineage: [
    {
      id: 'column-order-id',
      fromComponentId: 'orders-source',
      fromColumnId: 'orders-id',
      toComponentId: 'order-fact-target',
      toColumnId: 'fact-order-id',
    },
    {
      id: 'column-orders-customer',
      fromComponentId: 'orders-source',
      fromColumnId: 'orders-customer-id',
      toComponentId: 'order-fact-target',
      toColumnId: 'fact-customer-key',
    },
    {
      id: 'column-customers-customer',
      fromComponentId: 'customers-source',
      fromColumnId: 'customers-id',
      toComponentId: 'order-fact-target',
      toColumnId: 'fact-customer-key',
    },
  ],
};

describe('LineageGraph', () => {
  it('utrzymuje źródła po lewej, targety po prawej i rysuje strzałki tabel', () => {
    const { container } = render(
      <LineageGraph
        mapping={mapping}
        selectedNodeId="orders-source"
        onSelectNode={vi.fn()}
      />
    );

    const sources = screen.getByRole('list', { name: 'Źródła mappingu' });
    const targets = screen.getByRole('list', { name: 'Targety mappingu' });
    expect(within(sources).getByText('ORDERS')).toBeVisible();
    expect(within(sources).getByText('CUSTOMERS')).toBeVisible();
    expect(within(targets).getByText('ORDER_FACT')).toBeVisible();
    expect(container.querySelector('[data-edge-id="table-orders-target"]')).toHaveAttribute(
      'marker-end'
    );
    expect(container.querySelectorAll('[data-column-edge-id]')).toHaveLength(3);
    expect(screen.getByTestId('lineage-world')).toHaveAttribute(
      'data-layout-direction',
      'source-left-target-right'
    );
  });

  it('rozwija i zwija listę kolumn natywnym przyciskiem disclosure', async () => {
    const user = userEvent.setup();
    render(
      <LineageGraph
        mapping={mapping}
        selectedNodeId="orders-source"
        onSelectNode={vi.fn()}
      />
    );

    const collapse = screen.getByRole('button', { name: 'Zwiń kolumny ORDERS' });
    expect(collapse).toHaveAttribute('aria-expanded', 'true');
    expect(screen.getByRole('button', { name: 'Pokaż wpływ kolumny ORDERS.ORDER_ID' })).toBeVisible();

    await user.click(collapse);

    const expand = screen.getByRole('button', { name: 'Rozwiń kolumny ORDERS' });
    expect(expand).toHaveAttribute('aria-expanded', 'false');
    expect(
      screen.queryByRole('button', { name: 'Pokaż wpływ kolumny ORDERS.ORDER_ID' })
    ).not.toBeInTheDocument();
  });

  it('pokazuje downstream po kliknięciu źródła i upstream po kliknięciu targetu', async () => {
    const user = userEvent.setup();
    const { container } = render(
      <LineageGraph
        mapping={mapping}
        selectedNodeId="orders-source"
        onSelectNode={vi.fn()}
      />
    );

    const ordersCustomer = screen.getByRole('button', {
      name: 'Pokaż wpływ kolumny ORDERS.CUSTOMER_ID',
    });
    const customersCustomer = screen.getByRole('button', {
      name: 'Pokaż wpływ kolumny CUSTOMERS.CUSTOMER_ID',
    });
    const targetCustomer = screen.getByRole('button', {
      name: 'Pokaż źródła kolumny ORDER_FACT.CUSTOMER_KEY',
    });

    await user.click(ordersCustomer);

    expect(ordersCustomer).toHaveAttribute('aria-pressed', 'true');
    expect(targetCustomer).toHaveAttribute('data-related', 'true');
    expect(customersCustomer).toHaveAttribute('data-related', 'false');
    expect(screen.getByRole('status', { name: 'Podsumowanie zaznaczonej kolumny' })).toHaveTextContent(
      'ORDERS.CUSTOMER_ID wpływa na ORDER_FACT.CUSTOMER_KEY'
    );
    expect(
      container.querySelector('[data-column-edge-id="column-orders-customer"]')
    ).toHaveClass('graph-column-edge-active');
    expect(
      container.querySelector('[data-column-edge-id="column-customers-customer"]')
    ).toHaveClass('graph-edge-muted');

    await user.click(targetCustomer);

    expect(targetCustomer).toHaveAttribute('aria-pressed', 'true');
    expect(ordersCustomer).toHaveAttribute('data-related', 'true');
    expect(customersCustomer).toHaveAttribute('data-related', 'true');
    expect(screen.getByRole('status', { name: 'Podsumowanie zaznaczonej kolumny' })).toHaveTextContent(
      'ORDER_FACT.CUSTOMER_KEY zależy od ORDERS.CUSTOMER_ID, CUSTOMERS.CUSTOMER_ID'
    );
    expect(
      container.querySelector('[data-column-edge-id="column-orders-customer"]')
    ).toHaveClass('graph-column-edge-active');
    expect(
      container.querySelector('[data-column-edge-id="column-customers-customer"]')
    ).toHaveClass('graph-column-edge-active');
  });

  it('powiększa, pomniejsza i resetuje graf w zakresie 50–200%', async () => {
    const user = userEvent.setup();
    render(
      <LineageGraph
        mapping={mapping}
        selectedNodeId="orders-source"
        onSelectNode={vi.fn()}
      />
    );

    const zoomIn = screen.getByRole('button', { name: 'Powiększ graf' });
    const zoomOut = screen.getByRole('button', { name: 'Pomniejsz graf' });
    const reset = screen.getByRole('button', { name: 'Resetuj powiększenie grafu' });
    const output = screen.getByRole('status', { name: 'Poziom powiększenia grafu' });

    expect(output).toHaveTextContent('100%');
    await user.click(zoomIn);
    expect(output).toHaveTextContent('125%');
    await user.click(reset);
    expect(output).toHaveTextContent('100%');

    await user.click(zoomOut);
    await user.click(zoomOut);
    expect(output).toHaveTextContent('50%');
    expect(zoomOut).toBeDisabled();

    for (let index = 0; index < 6; index += 1) await user.click(zoomIn);
    expect(output).toHaveTextContent('200%');
    expect(zoomIn).toBeDisabled();
  });

  it('udostępnia tekstowy odpowiednik column lineage bez transformacji', () => {
    render(
      <LineageGraph
        mapping={mapping}
        selectedNodeId="orders-source"
        onSelectNode={vi.fn()}
      />
    );

    const table = screen.getByRole('table', { name: 'Tabelaryczny column lineage mappingu' });
    expect(within(table).getAllByText('CUSTOMER_ID')).toHaveLength(2);
    expect(within(table).getAllByText('CUSTOMER_KEY')).toHaveLength(2);
    expect(within(table).queryByText(/Filter|Expression|Join/i)).not.toBeInTheDocument();
  });

  it('ogranicza SVG i tabelę dla dużej liczby relacji', async () => {
    const user = userEvent.setup();
    const targetColumns = [
      { id: 'fact-order-id', name: 'ORDER_ID' },
      { id: 'fact-customer-key', name: 'CUSTOMER_KEY' },
      { id: 'fact-extra-1', name: 'EXTRA_1' },
      { id: 'fact-extra-2', name: 'EXTRA_2' },
      { id: 'fact-extra-3', name: 'EXTRA_3' },
    ];
    const largeMapping: MappingDetail = {
      ...mapping,
      id: 'map-large',
      nodes: mapping.nodes.map((node) =>
        node.id === 'order-fact-target' ? { ...node, columns: targetColumns } : node
      ),
      columnLineage: Array.from({ length: 1001 }, (_, index) => ({
        id: `large-edge-${index}`,
        fromComponentId: 'orders-source',
        fromColumnId: 'orders-id',
        toComponentId: 'order-fact-target',
        toColumnId: targetColumns[index % targetColumns.length].id,
      })),
    };
    const { container } = render(
      <LineageGraph
        mapping={largeMapping}
        selectedNodeId="orders-source"
        onSelectNode={vi.fn()}
      />
    );

    expect(container.querySelectorAll('[data-column-edge-id]')).toHaveLength(0);
    expect(
      screen.getByRole('status', { name: 'Podsumowanie zaznaczonej kolumny' })
    ).toHaveTextContent(
      'Graf zawiera 1001 zależności kolumnowych. Kliknij kolumnę, aby narysować tylko jej relacje.'
    );

    const table = screen.getByRole('table', { name: 'Tabelaryczny column lineage mappingu' });
    expect(within(table).getAllByRole('row')).toHaveLength(251);
    expect(screen.getByRole('status', { name: 'Licznik relacji column lineage' })).toHaveTextContent(
      'Wyświetlono 1–250 z 1001 relacji'
    );

    await user.click(screen.getByRole('button', { name: 'Następna strona relacji column lineage' }));

    expect(within(table).getAllByRole('row')).toHaveLength(251);
    expect(screen.getByRole('status', { name: 'Licznik relacji column lineage' })).toHaveTextContent(
      'Wyświetlono 251–500 z 1001 relacji'
    );
    expect(
      screen.getByRole('button', { name: 'Poprzednia strona relacji column lineage' })
    ).toBeEnabled();

    await user.click(
      screen.getByRole('button', { name: 'Pokaż wpływ kolumny ORDERS.ORDER_ID' })
    );

    expect(container.querySelectorAll('[data-column-edge-id]')).toHaveLength(1000);
    expect(container.querySelectorAll('.graph-column-edge-active')).toHaveLength(1000);
    expect(
      screen.getByRole('status', { name: 'Podsumowanie zaznaczonej kolumny' })
    ).toHaveTextContent(
      'ORDER_FACT.ORDER_ID, ORDER_FACT.CUSTOMER_KEY, ORDER_FACT.EXTRA_1 oraz 998 kolejnych relacji'
    );
    expect(
      screen.getByRole('status', { name: 'Podsumowanie zaznaczonej kolumny' })
    ).not.toHaveTextContent('ORDER_FACT.EXTRA_2');
    expect(
      screen.getByRole('status', { name: 'Podsumowanie zaznaczonej kolumny' })
    ).toHaveTextContent('Na diagramie pokazano 1000 z 1001 relacji');
  });
});
